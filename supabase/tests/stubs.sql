-- Simule le strict nécessaire de l'environnement Supabase pour tester schema.sql sur un Postgres nu.
create role anon nologin;
create role authenticated nologin;
create schema auth;
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('test.uid', true), '')::uuid $$;
grant usage on schema auth to authenticated, anon;
create schema storage;
create table storage.buckets (id text primary key, name text, public boolean);
create table storage.objects (id uuid primary key default gen_random_uuid(), bucket_id text, name text);
alter table storage.objects enable row level security;
grant usage on schema storage to authenticated;
grant select, insert, update on storage.objects to authenticated;
create publication supabase_realtime;
-- Supabase accorde par défaut les tables de `public` aux rôles API ; la RLS fait le tri.
alter default privileges in schema public grant select, insert, update, delete on tables to authenticated, anon;
grant usage on schema public to authenticated, anon;
