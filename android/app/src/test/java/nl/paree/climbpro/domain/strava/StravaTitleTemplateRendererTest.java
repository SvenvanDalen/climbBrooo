package nl.paree.climbpro.domain.strava;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StravaTitleTemplateRendererTest {

    @Test
    public void render_substitutesAllKnownPlaceholders() {
        StravaTitleTemplateRenderer.TitleContext ctx =
                StravaTitleTemplateRenderer.TitleContext.of("Col du Sample", 754, 12, 1180);

        String result = StravaTitleTemplateRenderer.render(
                "{climb} in {time}, PR-delta {delta}, VAM {vam}", ctx);

        assertEquals("Col du Sample in 12:34, PR-delta +0:12, VAM 1180 m/h", result);
    }

    @Test
    public void render_newPr_deltaRendersAsPr() {
        StravaTitleTemplateRenderer.TitleContext ctx =
                StravaTitleTemplateRenderer.TitleContext.of("Alp", 600, null, 900);

        String result = StravaTitleTemplateRenderer.render("{climb} {delta}", ctx);

        assertEquals("Alp PR", result);
    }

    @Test
    public void render_negativeDelta_alsoRendersAsPr() {
        // A negative delta means the new attempt IS the PR (elapsed < old PR).
        StravaTitleTemplateRenderer.TitleContext ctx =
                StravaTitleTemplateRenderer.TitleContext.of("Alp", 600, -30, 900);

        String result = StravaTitleTemplateRenderer.render("{delta}", ctx);

        assertEquals("PR", result);
    }

    @Test
    public void render_hourLongClimb_usesHmmssFormat() {
        StravaTitleTemplateRenderer.TitleContext ctx =
                StravaTitleTemplateRenderer.TitleContext.of("Big Alp", 3725, null, null);

        String result = StravaTitleTemplateRenderer.render("{time}", ctx);

        assertEquals("1:02:05", result);
    }

    @Test
    public void render_unknownPlaceholder_leftAsLiteralText() {
        StravaTitleTemplateRenderer.TitleContext ctx =
                StravaTitleTemplateRenderer.TitleContext.of("Alp", 60, null, null);

        String result = StravaTitleTemplateRenderer.render("{climb} {power} watts", ctx);

        assertEquals("Alp {power} watts", result);
    }

    @Test
    public void render_emptyTemplate_returnsEmptyString() {
        StravaTitleTemplateRenderer.TitleContext ctx =
                StravaTitleTemplateRenderer.TitleContext.of("Alp", 60, null, null);

        assertEquals("", StravaTitleTemplateRenderer.render("", ctx));
        assertEquals("", StravaTitleTemplateRenderer.render(null, ctx));
    }

    @Test
    public void render_noOpTemplate_passesThroughUnchanged() {
        StravaTitleTemplateRenderer.TitleContext ctx =
                StravaTitleTemplateRenderer.TitleContext.of("Alp", 60, null, null);

        String result = StravaTitleTemplateRenderer.render("Morning ride", ctx);

        assertEquals("Morning ride", result);
    }

    @Test
    public void render_nullContext_leavesEveryPlaceholderLiteral() {
        String result = StravaTitleTemplateRenderer.render("{climb} in {time}", null);

        assertEquals("{climb} in {time}", result);
    }

    @Test
    public void render_missingVam_substitutesEmptyString() {
        StravaTitleTemplateRenderer.TitleContext ctx =
                StravaTitleTemplateRenderer.TitleContext.of("Alp", 60, null, null);

        String result = StravaTitleTemplateRenderer.render("VAM: {vam}", ctx);

        assertEquals("VAM: ", result);
    }

    @Test
    public void deviatedAttempt_neverClaimsPr() {
        StravaTitleTemplateRenderer.TitleContext faster =
                StravaTitleTemplateRenderer.TitleContext.of("Col", 300, -20, null, false);
        StravaTitleTemplateRenderer.TitleContext first =
                StravaTitleTemplateRenderer.TitleContext.of("Col", 300, null, null, false);
        StravaTitleTemplateRenderer.TitleContext slower =
                StravaTitleTemplateRenderer.TitleContext.of("Col", 300, 15, null, false);

        assertEquals("Col []", StravaTitleTemplateRenderer.render("{climb} [{delta}]", faster));
        assertEquals("Col []", StravaTitleTemplateRenderer.render("{climb} [{delta}]", first));
        assertEquals("Col [+0:15]", StravaTitleTemplateRenderer.render("{climb} [{delta}]", slower));
    }
}
