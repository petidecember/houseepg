package com.anti.houseepg.interfaces.web;

import com.anti.houseepg.application.guide.ServeGuide;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Presentation adapter: the merged feed the DVR consumes, plus the per-source health the UI shows. */
@RestController
public class FeedController {

    private final ServeGuide serveGuide;
    private final XmltvFeedWriter writer;

    public FeedController(ServeGuide serveGuide, XmltvFeedWriter writer) {
        this.serveGuide = serveGuide;
        this.writer = writer;
    }

    @GetMapping(value = "/xmltv", produces = "application/xml;charset=UTF-8")
    public String xmltv() {
        return writer.write(serveGuide.current().guide());
    }

    @GetMapping("/api/sources/status")
    public List<SourceHealthResponse> sourceStatus() {
        return serveGuide.current().health().stream().map(SourceHealthResponse::from).toList();
    }
}
