package com.expensetracker.category;

import com.expensetracker.audit.AuditService;
import com.expensetracker.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CategoryService {

    public record CategoryDto(UUID id, UUID parentId, String name, String path, CategoryKind kind, String group,
                              String icon, String color, boolean archived, boolean systemDefault, boolean investment, List<CategoryDto> children) {
    }

    public record CategoryRequest(String name, UUID parentId, CategoryKind kind, String group, String icon, String color, Boolean investment) {
    }

    private final CategoryRepository repository;
    private final AuditService audit;

    public CategoryService(CategoryRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public void seedDefaults(UUID userId) {
        int order = 0;
        for (DefaultCategories.Def def : DefaultCategories.ALL) {
            Category parent = new Category(userId, null, def.name(), CategoryKind.valueOf(def.kind()));
            parent.setGroupName(def.group());
            parent.setIcon(def.icon());
            parent.setColor(def.color());
            parent.setSystemDefault(true);
            parent.setSortOrder(order++);
            parent.setInvestment(def.investment());
            repository.save(parent);
            int childOrder = 0;
            for (String childName : def.children()) {
                Category child = new Category(userId, parent.getId(), childName, parent.getKind());
                child.setGroupName(def.group());
                child.setIcon(def.icon());
                child.setColor(def.color());
                child.setSystemDefault(true);
                child.setSortOrder(childOrder++);
                child.setInvestment(def.investment());
                repository.save(child);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> tree(UUID userId, boolean includeArchived) {
        List<Category> all = repository.findByUserIdOrderBySortOrderAscNameAsc(userId);
        Map<UUID, Category> byId = new HashMap<>();
        all.forEach(c -> byId.put(c.getId(), c));
        return buildLevel(all, byId, null, includeArchived);
    }

    private List<CategoryDto> buildLevel(List<Category> all, Map<UUID, Category> byId, UUID parentId, boolean includeArchived) {
        List<CategoryDto> out = new ArrayList<>();
        for (Category c : all) {
            boolean sameParent = parentId == null ? c.getParentId() == null : parentId.equals(c.getParentId());
            if (!sameParent || (!includeArchived && c.isArchived())) continue;
            out.add(new CategoryDto(c.getId(), c.getParentId(), c.getName(), path(c, byId), c.getKind(), c.getGroupName(),
                    c.getIcon(), c.getColor(), c.isArchived(), c.isSystemDefault(), c.isInvestment(),
                    buildLevel(all, byId, c.getId(), includeArchived)));
        }
        return out;
    }

    /** Lookup map id → (name, path, top-level id, colour) for decorating transactions. */
    @Transactional(readOnly = true)
    public Map<UUID, CategoryInfo> infoMap(UUID userId) {
        List<Category> all = repository.findByUserIdOrderBySortOrderAscNameAsc(userId);
        Map<UUID, Category> byId = new HashMap<>();
        all.forEach(c -> byId.put(c.getId(), c));
        Map<UUID, CategoryInfo> out = new HashMap<>();
        for (Category c : all) {
            Category top = c.getParentId() == null ? c : byId.getOrDefault(c.getParentId(), c);
            out.put(c.getId(), new CategoryInfo(c.getId(), c.getName(), path(c, byId), top.getId(), top.getName(),
                    c.getColor() != null ? c.getColor() : top.getColor(), c.getIcon() != null ? c.getIcon() : top.getIcon(), c.getKind(),
                    top.isInvestment()));
        }
        return out;
    }

    public record CategoryInfo(UUID id, String name, String path, UUID topId, String topName, String color, String icon, CategoryKind kind,
                               boolean investment) {
    }

    @Transactional(readOnly = true)
    public Category require(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Category"));
    }

    /** Resolves "Parent" or "Parent > Child" (case-insensitive) to an active category of this user. */
    @Transactional(readOnly = true)
    public Optional<Category> resolvePath(UUID userId, String path) {
        if (path == null) return Optional.empty();
        String[] parts = path.split(">");
        List<Category> all = repository.findByUserIdOrderBySortOrderAscNameAsc(userId);
        String parentName = parts[0].trim().toLowerCase(Locale.ROOT);
        Optional<Category> parent = all.stream()
                .filter(c -> c.getParentId() == null && !c.isArchived() && c.getName().toLowerCase(Locale.ROOT).equals(parentName))
                .findFirst();
        if (parent.isEmpty() || parts.length == 1) return parent;
        String childName = parts[1].trim().toLowerCase(Locale.ROOT);
        Optional<Category> child = all.stream()
                .filter(c -> parent.get().getId().equals(c.getParentId()) && !c.isArchived()
                        && c.getName().toLowerCase(Locale.ROOT).equals(childName))
                .findFirst();
        return child.isPresent() ? child : parent; // fall back to the parent if the user archived/renamed the child
    }

    /**
     * Resolves what you typed in a CSV Category column: a path ("Food & Dining > Cafes"), or a category or
     * subcategory name ("Cafes", "groceries"). Ambiguous names (same subcategory under two parents) don't resolve.
     */
    @Transactional(readOnly = true)
    public Optional<Category> resolveByName(UUID userId, String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String t = text.trim();
        if (t.contains(">")) return resolvePath(userId, t);
        String key = t.toLowerCase(Locale.ROOT);
        List<Category> matches = repository.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .filter(c -> !c.isArchived() && c.getName().toLowerCase(Locale.ROOT).equals(key))
                .toList();
        if (matches.size() == 1) return Optional.of(matches.get(0));
        return matches.stream().filter(c -> c.getParentId() == null).findFirst();
    }

    @Transactional
    public CategoryDto create(UUID userId, CategoryRequest req) {
        String name = validName(req.name());
        CategoryKind kind = req.kind();
        Category parent = null;
        if (req.parentId() != null) {
            parent = require(userId, req.parentId());
            if (parent.getParentId() != null) throw ApiException.badRequest("Subcategories can be nested one level deep");
            kind = parent.getKind();
        }
        if (kind == null) throw ApiException.badRequest("Category kind (EXPENSE or INCOME) is required");
        Category c = new Category(userId, parent == null ? null : parent.getId(), name, kind);
        c.setGroupName(req.group() != null ? req.group() : (parent != null ? parent.getGroupName() : "Custom"));
        c.setIcon(req.icon() != null ? req.icon() : (parent != null ? parent.getIcon() : "tag"));
        c.setColor(validColor(req.color() != null ? req.color() : (parent != null ? parent.getColor() : "#64748B")));
        c.setSortOrder(1000);
        c.setInvestment(parent != null ? parent.isInvestment() : Boolean.TRUE.equals(req.investment()) && kind == CategoryKind.EXPENSE);
        repository.saveAndFlush(c);
        audit.record(userId, "CATEGORY_CREATED", "category", c.getId(), name);
        return toDto(userId, c);
    }

    @Transactional
    public CategoryDto update(UUID userId, UUID id, CategoryRequest req) {
        Category c = require(userId, id);
        if (req.name() != null) c.setName(validName(req.name()));
        if (req.icon() != null) c.setIcon(req.icon());
        if (req.color() != null) c.setColor(validColor(req.color()));
        if (req.group() != null) c.setGroupName(req.group());
        if (req.investment() != null && c.getParentId() == null && c.getKind() == CategoryKind.EXPENSE) {
            c.setInvestment(req.investment());
            repository.findByUserIdAndParentId(userId, c.getId()).forEach(ch -> ch.setInvestment(req.investment()));
        }
        repository.saveAndFlush(c);
        return toDto(userId, c);
    }

    @Transactional
    public void setArchived(UUID userId, UUID id, boolean archived) {
        Category c = require(userId, id);
        c.setArchived(archived);
        if (archived) {
            repository.findByUserIdAndParentId(userId, id).forEach(child -> child.setArchived(true));
        }
        audit.record(userId, archived ? "CATEGORY_ARCHIVED" : "CATEGORY_RESTORED", "category", id, c.getName());
    }

    /** Moves every transaction, split, rule and child from {@code sourceId} to {@code targetId}, then archives the source. */
    @Transactional
    public CategoryDto merge(UUID userId, UUID sourceId, UUID targetId) {
        if (sourceId.equals(targetId)) throw ApiException.badRequest("Cannot merge a category into itself");
        Category source = require(userId, sourceId);
        Category target = require(userId, targetId);
        if (source.getKind() != target.getKind()) throw ApiException.badRequest("Cannot merge an income category into an expense category");
        if (sourceId.equals(target.getParentId())) throw ApiException.badRequest("Cannot merge a category into its own subcategory");
        int moved = repository.reassignTransactions(userId, sourceId, targetId);
        repository.reassignSuggestions(userId, sourceId, targetId);
        repository.reassignSplits(userId, sourceId, targetId);
        repository.reassignRules(userId, sourceId, targetId);
        for (Category child : repository.findByUserIdAndParentId(userId, sourceId)) {
            if (target.getParentId() == null) child.setParentId(targetId);
            else {
                repository.reassignTransactions(userId, child.getId(), targetId);
                repository.reassignSuggestions(userId, child.getId(), targetId);
                repository.reassignSplits(userId, child.getId(), targetId);
                repository.reassignRules(userId, child.getId(), targetId);
                child.setArchived(true);
            }
        }
        source.setArchived(true);
        audit.record(userId, "CATEGORY_MERGED", "category", sourceId, source.getName() + " -> " + target.getName() + " (" + moved + " transactions)");
        return toDto(userId, target);
    }

    public record CategoryDeleteResult(int categoriesDeleted, int transactionsMoved, int transactionsBackToReview) {
    }

    /**
     * Permanently deletes a category and its subcategories. With {@code moveToId}, their transactions, splits and
     * rules move there first (same as merge). Without it, their transactions go back to Needs Review uncategorised,
     * split transactions touching them lose their split and go back to review, and their rules and budgets are removed.
     */
    @Transactional
    public CategoryDeleteResult delete(UUID userId, UUID id, UUID moveToId) {
        Category c = require(userId, id);
        String name = c.getName();
        List<UUID> ids = new java.util.ArrayList<>();
        ids.add(id);
        repository.findByUserIdAndParentId(userId, id).forEach(ch -> ids.add(ch.getId()));
        int moved = 0, reviewed = 0;
        if (moveToId != null) {
            if (ids.contains(moveToId)) throw ApiException.badRequest("Choose a category outside the one you are deleting");
            Category target = require(userId, moveToId);
            if (target.getKind() != c.getKind()) throw ApiException.badRequest("Move to a category of the same kind (income or expense)");
            for (UUID src : ids) {
                moved += repository.reassignTransactions(userId, src, moveToId);
                repository.reassignSuggestions(userId, src, moveToId);
                repository.reassignSplits(userId, src, moveToId);
                repository.reassignRules(userId, src, moveToId);
            }
        } else {
            reviewed += repository.resetSplitTransactions(userId, ids);
            repository.deleteSplitsTouching(userId, ids);
            reviewed += repository.resetTransactions(userId, ids);
        }
        int deleted = repository.deleteChildren(userId, id);
        deleted += repository.deleteOne(userId, id);
        audit.record(userId, "CATEGORY_DELETED", "category", id, name + (moveToId != null ? " (moved " + moved + ")" : " (" + reviewed + " back to review)"));
        return new CategoryDeleteResult(deleted, moved, reviewed);
    }

    private CategoryDto toDto(UUID userId, Category c) {
        return tree(userId, true).stream()
                .flatMap(d -> java.util.stream.Stream.concat(java.util.stream.Stream.of(d), d.children().stream()))
                .filter(d -> d.id().equals(c.getId()))
                .findFirst().orElseThrow();
    }

    private static String path(Category c, Map<UUID, Category> byId) {
        if (c.getParentId() == null) return c.getName();
        Category p = byId.get(c.getParentId());
        return p == null ? c.getName() : p.getName() + " > " + c.getName();
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("Category name is required");
        String n = name.trim();
        if (n.length() > 60) throw ApiException.badRequest("Category name must be at most 60 characters");
        if (n.contains(">")) throw ApiException.badRequest("Category name cannot contain '>'");
        return n;
    }

    private static String validColor(String color) {
        if (color == null || !color.matches("#[0-9A-Fa-f]{6}")) throw ApiException.badRequest("Colour must be a hex value like #22C55E");
        return color;
    }

    public static Comparator<CategoryDto> byName() {
        return Comparator.comparing(CategoryDto::name);
    }
}
