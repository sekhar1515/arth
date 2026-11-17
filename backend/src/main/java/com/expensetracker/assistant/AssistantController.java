package com.expensetracker.assistant;

import com.expensetracker.auth.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    public record AskRequest(String question) {
    }

    @PostMapping("/ask")
    public AssistantService.Answer ask(@RequestBody AskRequest req) {
        return service.ask(CurrentUser.id(), req.question());
    }

    @GetMapping("/examples")
    public List<String> examples() {
        return service.examples();
    }
}
