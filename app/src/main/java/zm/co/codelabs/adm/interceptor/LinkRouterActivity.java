package zm.co.codelabs.adm.interceptor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import zm.co.codelabs.adm.ui.MainActivity;

public final class LinkRouterActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); ArrayList<String> urls = new ArrayList<>(); Intent source = getIntent();
        if (Intent.ACTION_VIEW.equals(source.getAction()) && source.getData() != null) urls.addAll(UrlExtractor.extract(source.getDataString()));
        else {
            CharSequence text = source.getCharSequenceExtra(Intent.EXTRA_TEXT); if (text != null) urls.addAll(UrlExtractor.extract(text.toString()));
            ArrayList<CharSequence> many = source.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT); if (many != null) for (CharSequence item : many) urls.addAll(UrlExtractor.extract(item.toString()));
        }
        Intent target = new Intent(this, MainActivity.class).putStringArrayListExtra(MainActivity.EXTRA_URLS, new ArrayList<>(urls)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(target); finish();
    }
}
