package zm.co.codelabs.adm.ui.downloads;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import java.util.List;
import zm.co.codelabs.adm.App;
import zm.co.codelabs.adm.data.db.entity.DownloadEntity;

public final class DownloadsViewModel extends AndroidViewModel {
    private final LiveData<List<DownloadEntity>> downloads;
    public DownloadsViewModel(@NonNull Application app) { super(app); downloads = ((App) app).repository().observeAll(); }
    public LiveData<List<DownloadEntity>> downloads() { return downloads; }
}
