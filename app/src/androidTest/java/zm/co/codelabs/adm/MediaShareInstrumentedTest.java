package zm.co.codelabs.adm;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import zm.co.codelabs.adm.interceptor.LinkRouterActivity;

@RunWith(AndroidJUnit4.class)
public final class MediaShareInstrumentedTest {
    @Test public void sharedTubeLinkOpensBrowserWithMediaAction() {
        String url = "https://youtu.be/dQw4w9WgXcQ";
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        Intent share = new Intent(context, LinkRouterActivity.class)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        context.startActivity(share);

        onView(withId(R.id.address)).check(matches(withText(url)));
        onView(withId(R.id.media_download)).check(matches(isDisplayed()));
    }
}
