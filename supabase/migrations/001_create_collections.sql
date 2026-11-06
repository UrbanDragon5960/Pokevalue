-- PokeValue: private, per-user Pokémon collection storage
-- Run this once in the Supabase SQL Editor.

create table if not exists public.collections (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    card_id text not null,
    card_name text not null,
    set_name text not null default '',
    card_number text not null default '',
    image_url text not null default '',
    estimated_value_gbp numeric(12,2) not null default 0,
    price_source text not null default '',
    rarity text not null default '',
    quantity integer not null default 1 check (quantity >= 1),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint collections_user_card_unique unique (user_id, card_id)
);

create index if not exists collections_user_id_idx
    on public.collections (user_id);

alter table public.collections enable row level security;

-- A signed-in user can only read, add, edit, or delete their own rows.
drop policy if exists "Users read own collections" on public.collections;
create policy "Users read own collections"
    on public.collections for select
    to authenticated
    using (auth.uid() = user_id);

drop policy if exists "Users add to own collections" on public.collections;
create policy "Users add to own collections"
    on public.collections for insert
    to authenticated
    with check (auth.uid() = user_id);

drop policy if exists "Users update own collections" on public.collections;
create policy "Users update own collections"
    on public.collections for update
    to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "Users delete from own collections" on public.collections;
create policy "Users delete from own collections"
    on public.collections for delete
    to authenticated
    using (auth.uid() = user_id);

-- Keep updated_at current when a row changes.
create or replace function public.set_collections_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists collections_set_updated_at on public.collections;
create trigger collections_set_updated_at
    before update on public.collections
    for each row execute function public.set_collections_updated_at();

-- Do not grant anon access. Supabase's authenticated role is governed by RLS above.
grant select, insert, update, delete on public.collections to authenticated;
