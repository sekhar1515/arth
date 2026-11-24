-- New default category for existing users: Temple & Pooja (temple visits, pooja items, prasadam, hundi, dakshina),
-- placed next to Donations. Skipped for anyone who already has a top-level category with that name.
INSERT INTO categories (id, user_id, parent_id, name, kind, group_name, icon, color, system_default, sort_order)
SELECT gen_random_uuid(), u.id, NULL, 'Temple & Pooja', 'EXPENSE', 'Other', 'temple', '#EA580C', TRUE,
       COALESCE((SELECT d.sort_order FROM categories d
                 WHERE d.user_id = u.id AND d.parent_id IS NULL AND lower(d.name) = 'donations' LIMIT 1), 40)
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.user_id = u.id AND c.parent_id IS NULL AND lower(c.name) = 'temple & pooja');
