package ai.cc.chongming.review.api;

import ai.cc.chongming.review.config.RepositoryAccessProperties;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [AIREVIEW-PLAN-023#2] Exposes administrator-configured repository identities without physical roots.
 *
 * @author zyj
 */
@RestController
@RequestMapping("/api/repositories")
public class RepositoryOptionController {

    private final RepositoryAccessProperties repositoryAccessProperties;

    public RepositoryOptionController(RepositoryAccessProperties repositoryAccessProperties) {
        this.repositoryAccessProperties = repositoryAccessProperties;
    }

    @GetMapping
    public List<RepositoryOption> list() {
        return repositoryAccessProperties.allowed().stream()
                .map(repository -> new RepositoryOption(repository.id(), repository.displayName()))
                .toList();
    }

    /**
     * [AIREVIEW-PLAN-023#2] Public repository metadata deliberately excludes the server root.
     *
     * @author zyj
     */
    public record RepositoryOption(String id, String displayName) {
    }
}
