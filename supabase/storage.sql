-- =============================================================================================
-- Cap Mystère — stockage des médias (bucket privé « media »)
--   trips/<trip_id>/…        : images, audios et tuiles d'indice déposés par l'hôte
--   sessions/<session_id>/…  : photos de défis déposées par le voyageur
-- À exécuter après schema.sql.
-- =============================================================================================

insert into storage.buckets (id, name, public) values ('media', 'media', false) on conflict (id) do nothing;

-- Un média de voyage n'est lisible par un voyageur que s'il figure dans ce qui lui est déjà
-- débloqué : on cherche son chemin dans la projection « voyageur » de ses sessions.
create or replace function public.media_unlocked(p_name text) returns boolean
language sql stable security definer set search_path = public as $$
    select exists (
        select 1 from sessions s
         where s.traveler_id = auth.uid()
           and s.trip_id::text = split_part(p_name, '/', 2)
           and position('"' || p_name || '"' in _snapshot(s.id)::text) > 0);
$$;
revoke all on function public.media_unlocked(text) from public, anon;
grant execute on function public.media_unlocked(text) to authenticated;

drop policy if exists media_host_write on storage.objects;
create policy media_host_write on storage.objects for insert to authenticated
    with check (bucket_id = 'media' and split_part(name, '/', 1) = 'trips'
                and public.is_trip_host(nullif(split_part(name, '/', 2), '')::uuid));

drop policy if exists media_host_update on storage.objects;
create policy media_host_update on storage.objects for update to authenticated
    using (bucket_id = 'media' and split_part(name, '/', 1) = 'trips'
           and public.is_trip_host(nullif(split_part(name, '/', 2), '')::uuid));

drop policy if exists media_traveler_write on storage.objects;
create policy media_traveler_write on storage.objects for insert to authenticated
    with check (bucket_id = 'media' and split_part(name, '/', 1) = 'sessions'
                and exists (select 1 from public.sessions s where s.id::text = split_part(name, '/', 2) and s.traveler_id = auth.uid()));

drop policy if exists media_read on storage.objects;
create policy media_read on storage.objects for select to authenticated
    using (bucket_id = 'media' and (
        (split_part(name, '/', 1) = 'trips' and (public.is_trip_host(nullif(split_part(name, '/', 2), '')::uuid) or public.media_unlocked(name)))
        or (split_part(name, '/', 1) = 'sessions' and (
            public.is_session_host(nullif(split_part(name, '/', 2), '')::uuid)
            or exists (select 1 from public.sessions s where s.id::text = split_part(name, '/', 2) and s.traveler_id = auth.uid())))));
