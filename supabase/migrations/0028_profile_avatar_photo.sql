-- Foto de perfil real, alem dos 6 avatares internos (migration 0010). A
-- 0010 documentava que os avatares ficaram restritos a opcoes internas pra
-- evitar conteudo inadequado -- aqui reabro isso com duas salvaguardas:
-- recorte obrigatorio do lado do app (nao dá pra validar no banco, mas o
-- app so chama esta RPC depois de recortar) e denuncia (unica moderacao
-- viavel sem equipe dedicada). Revisao continua manual: eu consulto
-- avatar_photo_reports pelo SQL Editor/CLI e limpo a foto na mao quando
-- alguem denunciar, sem UI de admin.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('avatar-photos', 'avatar-photos', true, 5242880, array['image/jpeg', 'image/png'])
on conflict (id) do nothing;

create policy "avatar_photos_select_public"
on storage.objects for select
to authenticated
using (bucket_id = 'avatar-photos');

create policy "avatar_photos_insert_own_folder"
on storage.objects for insert
to authenticated
with check (
    bucket_id = 'avatar-photos'
    and auth.uid()::text = (storage.foldername(name))[1]
);

create policy "avatar_photos_update_own_folder"
on storage.objects for update
to authenticated
using (
    bucket_id = 'avatar-photos'
    and auth.uid()::text = (storage.foldername(name))[1]
)
with check (
    bucket_id = 'avatar-photos'
    and auth.uid()::text = (storage.foldername(name))[1]
);

create policy "avatar_photos_delete_own_folder"
on storage.objects for delete
to authenticated
using (
    bucket_id = 'avatar-photos'
    and auth.uid()::text = (storage.foldername(name))[1]
);

alter table public.profiles
add column if not exists avatar_photo_path text;

create or replace function public.set_profile_avatar_photo(p_path text)
returns public.profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_profile public.profiles;
begin
    if p_path is null or p_path !~ ('^' || auth.uid()::text || '/') then
        raise exception 'INVALID_AVATAR_PHOTO_PATH';
    end if;

    update public.profiles
    set avatar_photo_path = p_path,
        updated_at = now()
    where id = auth.uid()
    returning * into v_profile;

    if v_profile.id is null then
        raise exception 'PROFILE_NOT_FOUND';
    end if;

    return v_profile;
end;
$$;

revoke all on function public.set_profile_avatar_photo(text) from public;
grant execute on function public.set_profile_avatar_photo(text) to authenticated;

create or replace function public.clear_profile_avatar_photo()
returns public.profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_profile public.profiles;
begin
    update public.profiles
    set avatar_photo_path = null,
        updated_at = now()
    where id = auth.uid()
    returning * into v_profile;

    if v_profile.id is null then
        raise exception 'PROFILE_NOT_FOUND';
    end if;

    return v_profile;
end;
$$;

revoke all on function public.clear_profile_avatar_photo() from public;
grant execute on function public.clear_profile_avatar_photo() to authenticated;

create table if not exists public.avatar_photo_reports (
    reporter_id uuid not null references public.profiles(id) on delete cascade,
    reported_profile_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (reporter_id, reported_profile_id)
);

alter table public.avatar_photo_reports enable row level security;

grant insert on public.avatar_photo_reports to authenticated;

create policy "avatar_photo_reports_insert_own"
on public.avatar_photo_reports for insert
to authenticated
with check (reporter_id = auth.uid());

create or replace function public.report_avatar_photo(p_profile_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_profile_id = auth.uid() then
        raise exception 'CANNOT_REPORT_SELF';
    end if;

    if not exists (
        select 1 from public.profiles
        where id = p_profile_id and avatar_photo_path is not null
    ) then
        raise exception 'PROFILE_HAS_NO_PHOTO';
    end if;

    insert into public.avatar_photo_reports (reporter_id, reported_profile_id)
    values (auth.uid(), p_profile_id)
    on conflict do nothing;
end;
$$;

revoke all on function public.report_avatar_photo(uuid) from public;
grant execute on function public.report_avatar_photo(uuid) to authenticated;
