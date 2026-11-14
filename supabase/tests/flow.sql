-- Scénario complet : publication, anti-triche, validation d'étape, défis, photo validée par l'hôte, RLS.
\set ON_ERROR_STOP on
\set host   '''aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'''
\set trav   '''bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'''
\set intru  '''cccccccc-cccc-cccc-cccc-cccccccccccc'''
\set trip   '''11111111-1111-1111-1111-111111111111'''
\set doc `cat "$DOC"`
create temp table ctx (sid uuid, sub uuid);
grant all on ctx to authenticated;
set role authenticated;

-- Hôte : publication
select set_config('test.uid', :host, false);
select publish_trip(:trip, 'ABC234', :'doc'::jsonb);

-- Un autre hôte ne peut ni prendre le code ni écraser le voyage.
select set_config('test.uid', :intru, false);
do $$ begin
  begin perform publish_trip('22222222-2222-2222-2222-222222222222', 'ABC234', '{}'::jsonb); raise exception 'ECHEC: code dupliqué accepté';
  exception when others then if sqlerrm not like '%CODE_TAKEN%' then raise; end if; end;
  begin perform publish_trip('11111111-1111-1111-1111-111111111111', 'ZZZ999', '{}'::jsonb); raise exception 'ECHEC: écrasement accepté';
  exception when others then if sqlerrm not like '%FORBIDDEN%' then raise; end if; end;
end $$;

-- Voyageur : rejoint. Rien de la suite ne doit fuiter.
select set_config('test.uid', :trav, false);
insert into ctx (sid) select (join_trip('abc234') ->> 'sessionId')::uuid;
do $$ declare s jsonb; raw text; secret text; begin
  select traveler_snapshot(sid) into s from ctx; raw := s::text;
  assert (s -> 'target' -> 'position' ->> 'lat')::float = 47.10, 'cible = étape 1';
  assert jsonb_array_length(s -> 'unlocked') = 0, 'rien de débloqué';
  assert s -> 'enRouteChallenges' -> 0 ->> 'id' = 'c1r', 'défi en route du tronçon courant';
  assert (s ->> 'clueWordLength')::int = 6;
  foreach secret in array array['Vieux Pont','MESSAGE-UN','Cascade','MESSAGE-DEUX','Colmar','COLMAR','MESSAGE-FINAL','47.35','6.45','48.123456','7.654321','1765','INDICE-TEMPS','a.jpg'] loop
    assert position(secret in raw) = 0, 'FUITE: ' || secret;
  end loop;
  -- Lecture directe de la table des voyages : la RLS ne renvoie rien au voyageur.
  assert (select count(*) from trips) = 0, 'le voyageur ne lit pas trips';
  assert (select count(*) from sessions) = 1, 'le voyageur voit sa session';
end $$;

-- Défi en route (QCM, bonne réponse = index 1), puis tentative sur un défi encore verrouillé.
do $$ declare s jsonb; begin
  select submit_answer(sid, 'c1', '1765', '["1765"]'::jsonb) into s from ctx;
  assert s -> 'challengeStatus' -> 'c1' is null, 'défi d''étape verrouillé avant l''arrivée';
  select submit_answer(sid, 'c1r', '0', '["0"]'::jsonb) into s from ctx;
  assert s -> 'challengeStatus' ->> 'c1r' = 'PENDING', 'mauvaise réponse = nouvel essai';
  select submit_answer(sid, 'c1r', '1', '["1"]'::jsonb) into s from ctx;
  assert s -> 'challengeStatus' ->> 'c1r' = 'SUCCESS', 'empreinte Kotlin = empreinte SQL';
  assert s -> 'clues' -> 0 ->> 'id' = 'f0', 'récompense livrée';
end $$;

-- Validation d'étape : trop loin, mauvaise étape, puis dans le rayon ; idempotence.
do $$ declare s jsonb; begin
  begin perform validate_stage(sid, 0, 47.20, 6.10, 5) from ctx; raise exception 'ECHEC: validé trop loin';
  exception when others then if sqlerrm not like '%TOO_FAR%' then raise; end if; end;
  begin perform validate_stage(sid, 1, 47.35, 6.45, 5) from ctx; raise exception 'ECHEC: étape sautée';
  exception when others then if sqlerrm not like '%WRONG_STAGE%' then raise; end if; end;
  select validate_stage(sid, 0, 47.1005, 6.1005, 10) into s from ctx;
  assert (s ->> 'currentIndex')::int = 1;
  assert s -> 'unlocked' -> 0 -> 'message' ->> 'text' = 'MESSAGE-UN', 'message débloqué';
  assert (s -> 'target' -> 'position' ->> 'lat')::float = 47.35, 'cible suivante';
  assert position('MESSAGE-DEUX' in s::text) = 0 and position('Colmar' in s::text) = 0 and position('48.123456' in s::text) = 0, 'toujours pas de fuite';
  select validate_stage(sid, 0, 47.1005, 6.1005, 10) into s from ctx;
  assert (s ->> 'currentIndex')::int = 1, 'validation rejouée sans effet';
end $$;

-- Réponse libre : 3 essais ratés => FAILED + indice de consolation.
do $$ declare s jsonb; i int; begin
  for i in 1..3 loop select submit_answer(sid, 'c1', 'x', '["x"]'::jsonb) into s from ctx; end loop;
  assert s -> 'challengeStatus' ->> 'c1' = 'FAILED';
  assert s -> 'clues' -> 1 ->> 'id' = 'f1b', 'indice de consolation';
end $$;

select push_positions(sid, '[{"lat":47.2,"lon":6.2},{"lat":47.3,"lon":6.3}]'::jsonb) from ctx;

-- Étape 2 (passée par l'hôte), puis photo à valider.
select set_config('test.uid', :host, false);
select host_skip_stage(:trip);
select host_send_message(:trip, 'Courage !', true);
select set_config('test.uid', :trav, false);
do $$ declare s jsonb; v_sid uuid; begin
  select sid into v_sid from ctx;
  select traveler_snapshot(v_sid) into s;
  assert (s ->> 'currentIndex')::int = 2 and s -> 'messages' -> 0 ->> 'text' = 'Courage !';
  begin perform submit_answer(v_sid, 'c2', '', '[]'::jsonb, 'sessions/autre/p.jpg'); raise exception 'ECHEC: chemin photo étranger accepté';
  exception when others then if sqlerrm not like '%BAD_PHOTO_PATH%' then raise; end if; end;
  select submit_answer(v_sid, 'c2', '', '[]'::jsonb, 'sessions/' || v_sid || '/p.jpg') into s;
  assert s -> 'challengeStatus' ->> 'c2' = 'AWAITING_HOST';
  begin perform host_live('11111111-1111-1111-1111-111111111111'); raise exception 'ECHEC: suivi lisible par le voyageur';
  exception when others then if sqlerrm not like '%FORBIDDEN%' then raise; end if; end;
end $$;

select set_config('test.uid', :host, false);
do $$ declare l jsonb; begin
  l := host_live('11111111-1111-1111-1111-111111111111');
  assert (l -> 'lastPosition' ->> 'lat')::float = 47.3 and jsonb_array_length(l -> 'trail') = 2;
  assert l -> 'submissions' -> -1 ->> 'status' = 'AWAITING_HOST';
  perform host_review((l -> 'submissions' -> -1 ->> 'id')::uuid, true);
  assert (select count(*) from trips) = 1 and (select count(*) from positions) = 2, 'l''hôte lit ses données';
end $$;

select set_config('test.uid', :trav, false);
do $$ declare s jsonb; begin
  select traveler_snapshot(sid) into s from ctx;
  assert s -> 'challengeStatus' ->> 'c2' = 'SUCCESS' and s -> 'clues' -> -1 ->> 'id' = 'f2', 'photo validée => fragment';
  select validate_stage(sid, 2, 48.1234, 7.6543, 5) into s from ctx;
  assert (s ->> 'finished')::boolean and s -> 'target' = 'null'::jsonb and s -> 'unlocked' -> 2 -> 'message' ->> 'text' = 'MESSAGE-FINAL';
  assert (select count(*) from positions) = 0, 'le voyageur ne lit pas les positions';
end $$;

-- Un intrus ne voit rien et ne peut pas agir sur la session d'un autre.
select set_config('test.uid', :intru, false);
do $$ begin
  assert (select count(*) from sessions) = 0 and (select count(*) from messages) = 0 and (select count(*) from submissions) = 0;
  begin perform traveler_snapshot(sid) from ctx; raise exception 'ECHEC: session lisible par un tiers';
  exception when others then if sqlerrm not like '%SESSION_NOT_FOUND%' then raise; end if; end;
  begin perform _snapshot(sid) from ctx; raise exception 'ECHEC: fonction interne appelable';
  exception when insufficient_privilege then null; end;
end $$;

-- Stockage : l'image de l'étape 1 est lisible une fois l'étape débloquée ; pas par un intrus.
reset role;
insert into storage.objects (bucket_id, name) values ('media', 'trips/11111111-1111-1111-1111-111111111111/a.jpg'), ('media', 'trips/11111111-1111-1111-1111-111111111111/futur.jpg');
set role authenticated;
do $$ begin assert (select count(*) from storage.objects) = 0, 'intrus : aucun média'; end $$;
select set_config('test.uid', :trav, false);
do $$ begin assert (select array_agg(name) from storage.objects) = array['trips/11111111-1111-1111-1111-111111111111/a.jpg'], 'voyageur : seulement le média débloqué'; end $$;
select set_config('test.uid', :host, false);
do $$ begin assert (select count(*) from storage.objects) = 2, 'hôte : tous ses médias'; end $$;
reset role;
select 'TOUS LES TESTS SQL PASSENT' as resultat;
