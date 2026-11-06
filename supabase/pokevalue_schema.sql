-- Run this once in Supabase SQL Editor for PokeValue.
create table if not exists public.user_cards (
    user_id uuid not null references auth.users(id) on delete cascade,
    card_id text not null,
    card_data jsonb not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, card_id)
);

alter table public.user_cards enable row level security;

drop policy if exists "Users can read their own cards" on public.user_cards;
create policy "Users can read their own cards"
    on public.user_cards for select to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Users can add their own cards" on public.user_cards;
create policy "Users can add their own cards"
    on public.user_cards for insert to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users can update their own cards" on public.user_cards;
create policy "Users can update their own cards"
    on public.user_cards for update to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "Users can delete their own cards" on public.user_cards;
create policy "Users can delete their own cards"
    on public.user_cards for delete to authenticated
    using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.user_cards to authenticated;
