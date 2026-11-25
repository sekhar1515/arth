-- Vegetables and Fruits as sub-categories of Groceries, for existing users (new users get them by default).
-- Skipped where Groceries is missing or the sub-category already exists.
INSERT INTO categories (id, user_id, parent_id, name, kind, group_name, icon, color, system_default, sort_order)
SELECT gen_random_uuid(), g.user_id, g.id, v.name, g.kind, g.group_name, v.icon, g.color, TRUE, v.ord
FROM categories g
CROSS JOIN (VALUES ('Vegetables', 'carrot', 0), ('Fruits', 'apple', 1)) AS v(name, icon, ord)
WHERE g.parent_id IS NULL AND g.system_default AND lower(g.name) = 'groceries'
  AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.parent_id = g.id AND lower(c.name) = lower(v.name));
