-- =============================================================================================
-- Cap Mystère — schéma Supabase (Postgres)
--
-- Principe de confidentialité : le voyageur n'a AUCUN accès en lecture à la table `trips`.
-- Il ne passe que par des fonctions SECURITY DEFINER qui ne projettent que :
--   * les étapes déjà débloquées (titre, message, défis « à l'étape ») ;
--   * la cible du tronçon courant (position + rayon) et ses défis « en route » ;
--   * les fragments d'indices déjà gagnés.
-- La destination finale, les étapes futures, leurs messages et les récompenses à venir ne
-- quittent jamais le serveur. Ces fonctions sont la transcription SQL de
-- core/src/main/kotlin/app/capmystere/core/server/TripServer.kt (référence testée).
--
-- À exécuter une fois dans l'éditeur SQL de Supabase (idempotent : peut être rejoué).
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------------------------

create table if not exists public.trips (
    id          uuid primary key,
    host_id     uuid not null default auth.uid(),
    code        text not null unique check (code ~ '^[A-Z2-9]{6}$'),
    -- { "trip": voyage complet (hôte), "pub": projection voyageur pré-calculée, "rewards": récompenses par défi }
    doc         jsonb not null,
    published   boolean not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table if not exists public.sessions (
    id            uuid primary key default gen_random_uuid(),
    trip_id       uuid not null references public.trips (id) on delete cascade,
    traveler_id   uuid not null default auth.uid(),
    current_index int  not null default 0,
    finished      boolean not null default false,
    started_at    timestamptz not null default now(),
    status        jsonb not null default '{}'::jsonb,   -- id du défi -> PENDING | SUCCESS | FAILED | AWAITING_HOST
    attempts      jsonb not null default '{}'::jsonb,   -- id du défi -> nombre d'essais
    clues         jsonb not null default '[]'::jsonb,   -- fragments d'indices gagnés
    last_lat      double precision,
    last_lon      double precision,
    last_seen_at  timestamptz,
    unique (trip_id, traveler_id)
);

create table if not exists public.positions (
    id          bigint generated always as identity primary key,
    session_id  uuid not null references public.sessions (id) on delete cascade,
    lat         double precision not null,
    lon         double precision not null,
    recorded_at timestamptz not null default now()
);
create index if not exists positions_session_idx on public.positions (session_id, id);

create table if not exists public.submissions (
    id           uuid primary key default gen_random_uuid(),
    session_id   uuid not null references public.sessions (id) on delete cascade,
    challenge_id text not null,
    prompt       text not null default '',
    kind         text not null,
    answer       text not null default '',
    photo_path   text,
    status       text not null,
    created_at   timestamptz not null default now()
);
create index if not exists submissions_session_idx on public.submissions (session_id, created_at);

create table if not exists public.messages (
    id          uuid primary key default gen_random_uuid(),
    session_id  uuid not null references public.sessions (id) on delete cascade,
    text        text not null,
    is_clue     boolean not null default false,
    created_at  timestamptz not null default now()
);
create index if not exists messages_session_idx on public.messages (session_id, created_at);

-- ---------------------------------------------------------------------------------------------
-- Row Level Security
-- Aucune politique d'écriture pour le voyageur : toutes ses écritures passent par les fonctions.
-- ---------------------------------------------------------------------------------------------

alter table public.trips       enable row level security;
alter table public.sessions    enable row level security;
alter table public.positions   enable row level security;
alter table public.submissions enable row level security;
alter table public.messages    enable row level security;

create or replace function public.is_trip_host(p_trip uuid) returns boolean
language sql stable security definer set search_path = public as $$
    select exists (select 1 from trips where id = p_trip and host_id = auth.uid());
$$;

create or replace function public.is_session_host(p_session uuid) returns boolean
language sql stable security definer set search_path = public as $$
    select exists (select 1 from sessions s join trips t on t.id = s.trip_id where s.id = p_session and t.host_id = auth.uid());
$$;

drop policy if exists trips_host_all on public.trips;
create policy trips_host_all on public.trips for all to authenticated
    using (host_id = auth.uid()) with check (host_id = auth.uid());

-- Lecture des sessions : l'hôte du voyage (suivi en direct) et le voyageur lui-même (temps réel).
drop policy if exists sessions_read on public.sessions;
create policy sessions_read on public.sessions for select to authenticated
    using (traveler_id = auth.uid() or public.is_trip_host(trip_id));

drop policy if exists positions_host_read on public.positions;
create policy positions_host_read on public.positions for select to authenticated
    using (public.is_session_host(session_id));

drop policy if exists submissions_read on public.submissions;
create policy submissions_read on public.submissions for select to authenticated
    using (public.is_session_host(session_id)
           or exists (select 1 from public.sessions s where s.id = session_id and s.traveler_id = auth.uid()));

drop policy if exists messages_read on public.messages;
create policy messages_read on public.messages for select to authenticated
    using (public.is_session_host(session_id)
           or exists (select 1 from public.sessions s where s.id = session_id and s.traveler_id = auth.uid()));

-- ---------------------------------------------------------------------------------------------
-- Outils internes
-- ---------------------------------------------------------------------------------------------

create or replace function public._distance_m(lat1 double precision, lon1 double precision, lat2 double precision, lon2 double precision)
returns double precision language sql immutable as $$
    select 2 * 6371008.8 * asin(least(1, sqrt(
        power(sin(radians(lat2 - lat1) / 2), 2) + cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lon2 - lon1) / 2), 2))));
$$;

create or replace function public._ms(t timestamptz) returns bigint language sql immutable as $$
    select case when t is null then 0 else (extract(epoch from t) * 1000)::bigint end;
$$;

-- Projection « voyageur » d'une session. Interne : ne vérifie pas l'appelant.
create or replace function public._snapshot(p_session uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
    s        sessions;
    pub      jsonb;
    stages   jsonb;
    n        int;
    unlocked jsonb;
    cur      jsonb;
    timed    jsonb;
    msgs     jsonb;
begin
    select * into s from sessions where id = p_session;
    if not found then raise exception 'SESSION_NOT_FOUND' using errcode = 'P0002'; end if;
    select doc -> 'pub' into pub from trips where id = s.trip_id;
    stages := coalesce(pub -> 'stages', '[]'::jsonb);
    n := jsonb_array_length(stages);

    -- Étapes débloquées uniquement : celles d'index < current_index (toutes si le voyage est fini).
    select coalesce(jsonb_agg(jsonb_build_object(
               'index', ord - 1, 'title', st -> 'title', 'message', st -> 'message',
               'challenges', coalesce(st -> 'atStage', '[]'::jsonb)) order by ord), '[]'::jsonb)
      into unlocked
      from jsonb_array_elements(stages) with ordinality as e(st, ord)
     where ord <= (case when s.finished then n else least(s.current_index, n) end);

    -- Étape visée : on n'en livre que la position, le rayon et les défis « en route ».
    cur := case when s.finished then null else stages -> s.current_index end;

    select coalesce(jsonb_agg(tc -> 'fragment'), '[]'::jsonb) into timed
      from jsonb_array_elements(coalesce(pub -> 'timedClues', '[]'::jsonb)) tc
     where (tc ->> 'afterMinutes')::int <= extract(epoch from (now() - s.started_at)) / 60;

    select coalesce(jsonb_agg(jsonb_build_object('id', m.id, 'text', m.text, 'isClue', m.is_clue, 'at', _ms(m.created_at)) order by m.created_at), '[]'::jsonb)
      into msgs from messages m where m.session_id = s.id;

    return jsonb_build_object(
        'tripId', s.trip_id, 'sessionId', s.id,
        'title', pub -> 'title', 'themeId', pub -> 'themeId', 'audience', pub -> 'audience', 'profile', pub -> 'profile',
        'currentIndex', s.current_index, 'finished', s.finished,
        'target', case when cur is null then null else jsonb_build_object('position', cur -> 'position', 'radiusM', cur -> 'radiusM') end,
        'unlocked', unlocked,
        'enRouteChallenges', coalesce(cur -> 'enRoute', '[]'::jsonb),
        'challengeStatus', s.status,
        'clues', s.clues || timed,
        'clueWordLength', coalesce(pub -> 'clueWordLength', '0'::jsonb),
        'clueGrid', coalesce(pub -> 'clueGrid', '3'::jsonb),
        'messages', msgs,
        'positionIntervalS', coalesce(pub -> 'positionIntervalS', '10'::jsonb),
        'showStreetNames', coalesce(pub -> 'showStreetNames', 'false'::jsonb),
        'startedAt', _ms(s.started_at));
end $$;

-- Session de l'appelant, verrouillée pour mise à jour.
create or replace function public._own_session(p_session uuid) returns public.sessions
language plpgsql security definer set search_path = public as $$
declare s sessions;
begin
    select * into s from sessions where id = p_session and traveler_id = auth.uid() for update;
    if not found then raise exception 'SESSION_NOT_FOUND' using errcode = 'P0002'; end if;
    return s;
end $$;

-- ---------------------------------------------------------------------------------------------
-- API voyageur
-- ---------------------------------------------------------------------------------------------

create or replace function public.join_trip(p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare t trips; sid uuid;
begin
    if auth.uid() is null then raise exception 'NOT_AUTHENTICATED' using errcode = '28000'; end if;
    select * into t from trips where code = upper(p_code) and published;
    if not found then raise exception 'Code inconnu' using errcode = 'P0002'; end if;
    -- Rejoindre à nouveau reprend la session existante.
    insert into sessions (trip_id, traveler_id) values (t.id, auth.uid())
        on conflict (trip_id, traveler_id) do nothing;
    select id into sid from sessions where trip_id = t.id and traveler_id = auth.uid();
    return _snapshot(sid);
end $$;

create or replace function public.traveler_snapshot(p_session uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
    if not exists (select 1 from sessions where id = p_session and traveler_id = auth.uid()) then
        raise exception 'SESSION_NOT_FOUND' using errcode = 'P0002';
    end if;
    return _snapshot(p_session);
end $$;

-- Valide l'étape si la position est dans le rayon (+ tolérance GPS). Idempotent.
create or replace function public.validate_stage(p_session uuid, p_index int, p_lat double precision, p_lon double precision, p_accuracy double precision default 0)
returns jsonb language plpgsql security definer set search_path = public as $$
declare s sessions; stages jsonb; st jsonb; n int; allowed double precision;
begin
    s := _own_session(p_session);
    if p_index < s.current_index or s.finished then return _snapshot(p_session); end if;
    if p_index <> s.current_index then raise exception 'WRONG_STAGE' using errcode = 'P0001'; end if;
    select doc -> 'pub' -> 'stages' into stages from trips where id = s.trip_id;
    n := jsonb_array_length(stages);
    st := stages -> p_index;
    if st is null then raise exception 'WRONG_STAGE' using errcode = 'P0001'; end if;
    allowed := (st ->> 'radiusM')::double precision + 60 + least(greatest(coalesce(p_accuracy, 0), 0), 100);
    if _distance_m(p_lat, p_lon, (st -> 'position' ->> 'lat')::double precision, (st -> 'position' ->> 'lon')::double precision) > allowed then
        raise exception 'TOO_FAR' using errcode = 'P0001';
    end if;
    update sessions set current_index = current_index + 1, finished = (current_index + 1 >= n),
                        last_lat = p_lat, last_lon = p_lon, last_seen_at = now()
     where id = p_session;
    return _snapshot(p_session);
end $$;

-- Réponse à un défi. `p_canonicals` : formes normalisées de la réponse, calculées par le client ;
-- le serveur ne compare que leurs empreintes SHA-256 salées à celles du défi.
create or replace function public.submit_answer(p_session uuid, p_challenge text, p_answer text, p_canonicals jsonb, p_photo_path text default null, p_gave_up boolean default false)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
    s sessions; doc jsonb; ch jsonb; rw jsonb; previous text; tries int; outcome text; max_attempts int; correct boolean; frag jsonb;
begin
    s := _own_session(p_session);
    select t.doc into doc from trips t where t.id = s.trip_id;

    -- Le défi doit appartenir à une étape débloquée, ou au tronçon courant s'il est « en route ».
    select c into ch
      from jsonb_array_elements(doc -> 'pub' -> 'stages') with ordinality as e(st, ord),
           lateral (
               select x as c, 'AT' as place from jsonb_array_elements(coalesce(st -> 'atStage', '[]'::jsonb)) x
               union all
               select x, 'ROUTE' from jsonb_array_elements(coalesce(st -> 'enRoute', '[]'::jsonb)) x
           ) l
     where l.c ->> 'id' = p_challenge
       and ((l.place = 'AT' and (ord - 1 < s.current_index or s.finished)) or (l.place = 'ROUTE' and ord - 1 <= s.current_index))
     limit 1;
    if ch is null then return _snapshot(p_session); end if;

    previous := s.status ->> p_challenge;
    if previous in ('SUCCESS', 'FAILED') then return _snapshot(p_session); end if;

    rw := coalesce(doc -> 'rewards' -> p_challenge, '{}'::jsonb);
    max_attempts := coalesce((doc -> 'pub' ->> 'maxAttempts')::int, 3);
    tries := coalesce((s.attempts ->> p_challenge)::int, 0) + 1;

    if p_gave_up then
        outcome := 'FAILED';
    elsif coalesce((rw ->> 'isPhoto')::boolean, false) then
        if p_photo_path is null then return _snapshot(p_session); end if;
        -- La photo doit avoir été déposée dans le dossier de cette session.
        if p_photo_path not like 'sessions/' || p_session::text || '/%' then raise exception 'BAD_PHOTO_PATH' using errcode = 'P0001'; end if;
        outcome := case when coalesce((rw ->> 'hostValidation')::boolean, false) then 'AWAITING_HOST' else 'SUCCESS' end;
    elsif jsonb_array_length(coalesce(ch -> 'answerHashes', '[]'::jsonb)) = 0 then
        outcome := 'SUCCESS';   -- défi sans bonne réponse définie : participer suffit
    else
        select exists (
            select 1 from jsonb_array_elements_text(coalesce(p_canonicals, '[]'::jsonb)) c
             where (ch -> 'answerHashes') ? encode(sha256(convert_to((ch ->> 'salt') || ':' || c, 'UTF8')), 'hex')
        ) into correct;
        outcome := case when correct then 'SUCCESS' when tries >= max_attempts then 'FAILED' else 'PENDING' end;
    end if;

    frag := case outcome when 'SUCCESS' then rw -> 'reward' when 'FAILED' then rw -> 'failClue' else null end;

    delete from submissions where session_id = p_session and challenge_id = p_challenge and status = 'AWAITING_HOST';
    insert into submissions (session_id, challenge_id, prompt, kind, answer, photo_path, status)
    values (p_session, p_challenge, coalesce(ch ->> 'prompt', ''), coalesce(ch ->> 'kind', 'FREE_TEXT'), coalesce(p_answer, ''), p_photo_path, outcome);

    update sessions
       set attempts = attempts || jsonb_build_object(p_challenge, tries),
           status   = status || jsonb_build_object(p_challenge, outcome),
           clues    = case when frag is null or jsonb_typeof(frag) = 'null' then clues else clues || jsonb_build_array(frag) end
     where id = p_session;
    return _snapshot(p_session);
end $$;

create or replace function public.push_positions(p_session uuid, p_points jsonb) returns void
language plpgsql security definer set search_path = public as $$
declare last jsonb;
begin
    perform _own_session(p_session);
    insert into positions (session_id, lat, lon)
    select p_session, (p ->> 'lat')::double precision, (p ->> 'lon')::double precision
      from jsonb_array_elements(coalesce(p_points, '[]'::jsonb)) with ordinality as e(p, ord) order by ord;
    last := p_points -> (jsonb_array_length(coalesce(p_points, '[]'::jsonb)) - 1);
    update sessions set last_seen_at = now(),
                        last_lat = coalesce((last ->> 'lat')::double precision, last_lat),
                        last_lon = coalesce((last ->> 'lon')::double precision, last_lon)
     where id = p_session;
end $$;

-- ---------------------------------------------------------------------------------------------
-- API hôte
-- ---------------------------------------------------------------------------------------------

create or replace function public.publish_trip(p_id uuid, p_code text, p_doc jsonb) returns text
language plpgsql security definer set search_path = public as $$
begin
    if auth.uid() is null then raise exception 'NOT_AUTHENTICATED' using errcode = '28000'; end if;
    if exists (select 1 from trips where id = p_id and host_id <> auth.uid()) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
    if exists (select 1 from trips where code = p_code and id <> p_id) then raise exception 'CODE_TAKEN' using errcode = 'P0001'; end if;
    insert into trips (id, host_id, code, doc, published) values (p_id, auth.uid(), p_code, p_doc, true)
        on conflict (id) do update set code = excluded.code, doc = excluded.doc, published = true, updated_at = now();
    return p_code;
end $$;

create or replace function public.unpublish_trip(p_trip uuid) returns void
language sql security definer set search_path = public as $$
    delete from trips where id = p_trip and host_id = auth.uid();
$$;

-- Dernière session du voyage, au format attendu par l'application (LiveSession), ou null.
create or replace function public.host_live(p_trip uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare s sessions; trail jsonb; subs jsonb; msgs jsonb;
begin
    if not is_trip_host(p_trip) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
    select * into s from sessions where trip_id = p_trip order by started_at desc limit 1;
    if not found then return null; end if;
    select coalesce(jsonb_agg(jsonb_build_object('lat', lat, 'lon', lon) order by id), '[]'::jsonb) into trail
      from (select id, lat, lon from positions where session_id = s.id order by id desc limit 2000) p;
    select coalesce(jsonb_agg(jsonb_build_object(
               'id', id, 'challengeId', challenge_id, 'prompt', prompt, 'kind', kind, 'answer', answer,
               'photo', case when photo_path is null then null else jsonb_build_object('remotePath', photo_path) end,
               'status', status, 'at', _ms(created_at)) order by created_at), '[]'::jsonb)
      into subs from submissions where session_id = s.id;
    select coalesce(jsonb_agg(jsonb_build_object('id', id, 'text', text, 'isClue', is_clue, 'at', _ms(created_at)) order by created_at), '[]'::jsonb)
      into msgs from messages where session_id = s.id;
    return jsonb_build_object(
        'sessionId', s.id, 'currentIndex', s.current_index, 'finished', s.finished,
        'lastPosition', case when s.last_lat is null then null else jsonb_build_object('lat', s.last_lat, 'lon', s.last_lon) end,
        'lastSeenAt', _ms(s.last_seen_at), 'startedAt', _ms(s.started_at),
        'trail', trail, 'submissions', subs, 'messages', msgs);
end $$;

create or replace function public._host_session(p_trip uuid) returns uuid
language plpgsql security definer set search_path = public as $$
declare sid uuid;
begin
    if not is_trip_host(p_trip) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
    select id into sid from sessions where trip_id = p_trip order by started_at desc limit 1;
    if sid is null then raise exception 'NO_SESSION' using errcode = 'P0002'; end if;
    return sid;
end $$;

create or replace function public.host_send_message(p_trip uuid, p_text text, p_is_clue boolean default false) returns void
language plpgsql security definer set search_path = public as $$
begin
    insert into messages (session_id, text, is_clue) values (_host_session(p_trip), p_text, coalesce(p_is_clue, false));
end $$;

create or replace function public.host_skip_stage(p_trip uuid) returns void
language plpgsql security definer set search_path = public as $$
declare sid uuid; n int;
begin
    sid := _host_session(p_trip);
    select jsonb_array_length(doc -> 'pub' -> 'stages') into n from trips where id = p_trip;
    update sessions set current_index = current_index + 1, finished = (current_index + 1 >= n) where id = sid and not finished;
end $$;

-- Validation ou refus d'une photo en attente.
create or replace function public.host_review(p_submission uuid, p_approved boolean) returns void
language plpgsql security definer set search_path = public as $$
declare sub submissions; rw jsonb; outcome text; frag jsonb;
begin
    select * into sub from submissions where id = p_submission and status = 'AWAITING_HOST' for update;
    if not found then return; end if;
    if not is_session_host(sub.session_id) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
    select t.doc -> 'rewards' -> sub.challenge_id into rw from sessions s join trips t on t.id = s.trip_id where s.id = sub.session_id;
    outcome := case when p_approved then 'SUCCESS' else 'FAILED' end;
    frag := case when p_approved then rw -> 'reward' else rw -> 'failClue' end;
    update submissions set status = outcome where id = p_submission;
    update sessions
       set status = status || jsonb_build_object(sub.challenge_id, outcome),
           clues  = case when frag is null or jsonb_typeof(frag) = 'null' then clues else clues || jsonb_build_array(frag) end
     where id = sub.session_id;
end $$;

-- ---------------------------------------------------------------------------------------------
-- Droits d'exécution : uniquement les utilisateurs authentifiés (anonymes compris), jamais `anon`.
-- Les fonctions préfixées par « _ » sont internes.
-- ---------------------------------------------------------------------------------------------

revoke all on all functions in schema public from public, anon;
grant execute on function
    public.join_trip(text), public.traveler_snapshot(uuid),
    public.validate_stage(uuid, int, double precision, double precision, double precision),
    public.submit_answer(uuid, text, text, jsonb, text, boolean), public.push_positions(uuid, jsonb),
    public.publish_trip(uuid, text, jsonb), public.unpublish_trip(uuid), public.host_live(uuid),
    public.host_send_message(uuid, text, boolean), public.host_skip_stage(uuid), public.host_review(uuid, boolean),
    public.is_trip_host(uuid), public.is_session_host(uuid)
to authenticated;

-- ---------------------------------------------------------------------------------------------
-- Temps réel : l'hôte et le voyageur sont prévenus des changements (la RLS filtre ce qu'ils voient).
-- ---------------------------------------------------------------------------------------------

do $$
declare t text;
begin
    foreach t in array array['sessions', 'submissions', 'messages'] loop
        if not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = t) then
            execute format('alter publication supabase_realtime add table public.%I', t);
        end if;
    end loop;
end $$;
