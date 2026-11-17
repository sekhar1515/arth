package com.expensetracker.category;

import com.expensetracker.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryService.CategoryDto> tree(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.tree(CurrentUser.id(), includeArchived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryService.CategoryDto create(@RequestBody CategoryService.CategoryRequest req) {
        return service.create(CurrentUser.id(), req);
    }

    @PutMapping("/{id}")
    public CategoryService.CategoryDto update(@PathVariable UUID id, @RequestBody CategoryService.CategoryRequest req) {
        return service.update(CurrentUser.id(), id, req);
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        service.setArchived(CurrentUser.id(), id, true);
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID id) {
        service.setArchived(CurrentUser.id(), id, false);
    }

    @PostMapping("/{id}/merge-into/{targetId}")
    public CategoryService.CategoryDto merge(@PathVariable UUID id, @PathVariable UUID targetId) {
        return service.merge(CurrentUser.id(), id, targetId);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public CategoryService.CategoryDeleteResult delete(@PathVariable UUID id, @RequestParam(required = false) UUID moveTo) {
        return service.delete(com.expensetracker.auth.CurrentUser.id(), id, moveTo);
    }
}
