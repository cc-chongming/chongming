package ai.cc.chongming.review.api;

import ai.cc.chongming.auth.api.PrincipalAccessor;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.domain.UserRole;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementVisibility;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;

/**
 * [AIREVIEW-PLAN-027] Derives the requirement visibility scope from the request principal.
 * Missing principals keep the open demo/test-profile behaviour ({@code null}); administrators
 * see everything ({@code null}); every other role is scoped to the requirements it created plus
 * the requirements bound to its dev tasks.
 *
 * @author wangli
 */
public final class RequirementVisibilityResolver {

    private final PrincipalAccessor principalAccessor = new PrincipalAccessor();
    private final DevTaskRepository devTaskRepository;

    public RequirementVisibilityResolver(DevTaskRepository devTaskRepository) {
        this.devTaskRepository = devTaskRepository;
    }

    /**
     * Resolves the viewer scope for the current request.
     *
     * @param request current servlet request carrying the optional principal attribute
     * @return viewer visibility, {@code null} when the caller sees every requirement
     */
    public RequirementVisibility visibilityFor(HttpServletRequest request) {
        Optional<AuthPrincipal> principal = principalAccessor.requirePrincipal(request);
        if (principal.isEmpty()) {
            // Demo/test profiles without the authentication module keep full visibility.
            return null;
        }
        AuthPrincipal authPrincipal = principal.get();
        UserRole role = UserRole.parse(authPrincipal.role());
        if (role.viewsAllRequirements()) {
            return null;
        }
        Set<RequirementId> assignedRequirementIds = devTaskRepository == null
                ? Set.of()
                : devTaskRepository.findRequirementIdsByAssignee(authPrincipal.username());
        return new RequirementVisibility(authPrincipal.username(), assignedRequirementIds);
    }
}
