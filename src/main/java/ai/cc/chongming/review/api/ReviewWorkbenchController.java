package ai.cc.chongming.review.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the review workbench entry page from its static-resource directory.
 *
 * @author wangli
 */
@Controller
public class ReviewWorkbenchController {

    /**
     * Resolves the workbench directory URL to its Vite-generated entry page.
     *
     * @return static workbench entry page
     */
    @GetMapping({"/review", "/review/"})
    public String workbench() {
        return "forward:/review/index.html";
    }
}
