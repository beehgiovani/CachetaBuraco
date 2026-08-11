-- O nome do usuario ja era protegido por sender_id = auth.uid(), mas o
-- assento ainda vinha livre no insert. Confiro o assento na propria RLS para
-- uma versao alterada do app nao conseguir falar como se fosse outro jogador.

drop policy if exists "room_chat_messages_insert_member" on public.room_chat_messages;

create policy "room_chat_messages_insert_member"
on public.room_chat_messages for insert
to authenticated
with check (
    sender_id = (select auth.uid())
    and (select private.is_room_member(room_id))
    and sender_seat = (select private.current_room_seat(room_id))
);
