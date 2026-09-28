package zm.co.codelabs.adm;

import static org.junit.Assert.*;
import org.junit.Test;
import zm.co.codelabs.adm.storage.FilenameSanitizer;
import zm.co.codelabs.adm.util.ContentDisposition;

public class FilenameAndHeaderTest {
    @Test public void sanitizesTraversalAndReservedNames() { assertEquals(".._evil_.apk", FilenameSanitizer.sanitize("../evil?.apk")); assertEquals("_CON", FilenameSanitizer.sanitize("CON")); }
    @Test public void parsesUtf8Filename() { assertEquals("hello world.zip", ContentDisposition.filename("attachment; filename*=UTF-8''hello%20world.zip")); assertEquals("report.pdf", ContentDisposition.filename("attachment; filename=\"report.pdf\"")); }
}
