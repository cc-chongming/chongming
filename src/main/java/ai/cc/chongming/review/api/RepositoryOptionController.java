package ai.cc.chongming.review.api;

import ai.cc.chongming.review.config.RepositoryAccessProperties;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [AIREVIEW-PLAN-023#2] Exposes administrator-configured repository identities without physical roots.
 * [AIREVIEW-PLAN-028] Also exposes the source type so the workbench can label local and remote entries.
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
                .map(repository -> new RepositoryOption(
                        repository.id(), repository.displayName(), typeOf(repository)))
                .toList();
    }

    /** [AIREVIEW-PLAN-028] Maps the configured source type to its public label. */
    private String typeOf(RepositoryAccessProperties.RepositoryDefinition repository) {
        return repository.type() == RepositoryAccessProperties.RepositoryDefinition.RepositoryType.REMOTE
                ? "remote" : "local";
    }

    /**
     * [AIREVIEW-PLAN-023#2] Public repository metadata deliberately excludes the server root.
     * [AIREVIEW-PLAN-028] {@code type} is {@code local} or {@code remote}.
     *
     * @author zyj
     */
    public record RepositoryOption(String id, String displayName, String type) {
    }
}
