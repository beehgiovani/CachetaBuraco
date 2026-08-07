-- Os avatares do Beta sao internos ao aplicativo. Assim eu nao preciso aceitar
-- upload ou URL externa no perfil e evito conteudo inadequado e rastreamento.

update public.profiles
set avatar_url = null,
    updated_at = now()
where avatar_url is not null
  and avatar_url not in (
      'builtin:emerald',
      'builtin:gold',
      'builtin:ruby',
      'builtin:sapphire',
      'builtin:violet',
      'builtin:graphite'
  );

alter table public.profiles
drop constraint if exists profiles_avatar_id_check;

alter table public.profiles
add constraint profiles_avatar_id_check check (
    avatar_url is null
    or avatar_url in (
        'builtin:emerald',
        'builtin:gold',
        'builtin:ruby',
        'builtin:sapphire',
        'builtin:violet',
        'builtin:graphite'
    )
);

create or replace function public.set_profile_avatar(p_avatar_id text)
returns table (
    profile_id uuid,
    nickname text,
    avatar_url text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_avatar_id text := lower(trim(coalesce(p_avatar_id, '')));
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    if v_avatar_id not in (
        'builtin:emerald',
        'builtin:gold',
        'builtin:ruby',
        'builtin:sapphire',
        'builtin:violet',
        'builtin:graphite'
    ) then
        raise exception 'INVALID_AVATAR' using errcode = 'P0001';
    end if;

    update public.profiles profile
    set avatar_url = v_avatar_id,
        updated_at = now()
    where profile.id = v_user_id;

    if not found then
        raise exception 'PROFILE_NOT_FOUND' using errcode = 'P0001';
    end if;

    return query
    select profile.id, profile.nickname, profile.avatar_url
    from public.profiles profile
    where profile.id = v_user_id;
end;
$$;

revoke all on function public.set_profile_avatar(text) from public;
grant execute on function public.set_profile_avatar(text) to authenticated;
