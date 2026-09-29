package zm.co.codelabs.adm.interceptor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import zm.co.codelabs.adm.ui.MainActivity;
import zm.co.codelabs.adm.media.MediaDiscovery;

public final class LinkRouterActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); ArrayList<String> urls = new ArrayList<>(); Intent source = getIntent();
        if (Intent.ACTION_VIEW.equals(source.getAction()) && source.getData() != null) urls.addAll(UrlExtractor.extract(source.getDataString()));
        else if (Intent.ACTION_SEND_MULTIPLE.equals(source.getAction())) {
            ArrayList<CharSequence> many = source.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT); if (many != null) for (CharSequence item : many) urls.addAll(UrlExtractor.extract(item.toString()));
        } else {
            CharSequence text = source.getCharSequenceExtra(Intent.EXTRA_TEXT); if (text != null) urls.addAll(UrlExtractor.extract(text.toString()));
            if (urls.isEmpty() && source.getParcelableExtra(Intent.EXTRA_STREAM) != null) {
                Object stream = source.getParcelableExtra(Intent.EXTRA_STREAM);
                urls.addAll(UrlExtractor.extract(String.valueOf(stream)));
            }
        }
        Intent target = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (urls.size() == 1 && MediaDiscovery.isMediaPageUrl(urls.get(0))) target.putExtra(MainActivity.EXTRA_BROWSER_URL, urls.get(0));
        else target.putStringArrayListExtra(MainActivity.EXTRA_URLS, new ArrayList<>(urls));
        startActivity(target); finish();
    }
}
