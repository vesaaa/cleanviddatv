package io.github.vesaaa.cleanviddatv;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Answers apps that ask to pick a file (GET_CONTENT, OPEN_DOCUMENT) or a folder (OPEN_DOCUMENT_TREE). China-market
 * Hisense / Vidda firmware has no DocumentsUI, so those requests found nothing: Lemuroid could not be given its ROM
 * folder. The remote browses the volumes and folders here; the choice goes back as a StorageProvider URI that the
 * asking app is granted (persistably, and for a folder everything under it).
 */
public class FilePickerActivity extends Activity {

    private static final String PREFS = "file_picker";

    private boolean pickFolder;
    private boolean openDocument;
    private List<String> mimeTypes = Collections.emptyList();
    /** The folder shown, or null for the list of volumes. */
    private File current;
    private final List<Row> rows = new ArrayList<>();
    private final RowAdapter adapter = new RowAdapter();
    private TextView path, useFolder, empty;
    private RecyclerView list;

    /** One line: a volume, a folder or a file. */
    private static final class Row {
        final File file;
        final String name, meta;
        final int icon;
        final boolean dimmed;

        Row(File file, String name, String meta, int icon, boolean dimmed) {
            this.file = file;
            this.name = name;
            this.meta = meta;
            this.icon = icon;
            this.dimmed = dimmed;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_picker);
        Intent intent = getIntent();
        String action = intent.getAction();
        pickFolder = Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action);
        openDocument = Intent.ACTION_OPEN_DOCUMENT.equals(action);
        mimeTypes = requestedTypes(intent);
        ((TextView) findViewById(R.id.pickerTitle)).setText(pickFolder ? R.string.files_pick_folder : R.string.files_pick_file);
        path = findViewById(R.id.pickerPath);
        useFolder = findViewById(R.id.pickerUseFolder);
        empty = findViewById(R.id.pickerEmpty);
        list = findViewById(R.id.pickerList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        useFolder.setOnClickListener(v -> {
            if (current != null) finishWith(current);
        });
        setResult(RESULT_CANCELED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            empty.setText(R.string.files_no_access);
            empty.setVisibility(View.VISIBLE);
            return;
        }
        File start = FileVolumes.resolve(this, prefs().getString(pickFolder ? "folder" : "file", null));
        List<FileVolumes.Volume> volumes = FileVolumes.list(this);
        if (start == null && volumes.size() == 1) start = volumes.get(0).dir;
        show(start != null && start.isDirectory() ? start : null);
    }

    /** The MIME types the app asked for: the intent's type and EXTRA_MIME_TYPES; empty means any. */
    private static List<String> requestedTypes(Intent intent) {
        List<String> types = new ArrayList<>();
        String[] extra = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (extra != null) types.addAll(Arrays.asList(extra));
        if (intent.getType() != null) types.add(intent.getType());
        return types;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void show(File dir) {
        current = dir;
        rows.clear();
        if (dir == null) {
            path.setText(R.string.files_storage);
            for (FileVolumes.Volume v : FileVolumes.list(this)) {
                rows.add(new Row(v.dir, v.title, getString(R.string.files_free,
                        Formatter.formatShortFileSize(this, v.dir.getUsableSpace())), R.drawable.ic_file_storage, false));
            }
        } else {
            path.setText(dir.getAbsolutePath());
            File[] children = dir.listFiles();
            if (children != null) {
                Arrays.sort(children, FileVolumes.ORDER);
                for (File f : children) {
                    if (f.getName().startsWith(".")) continue;
                    if (f.isDirectory()) {
                        rows.add(new Row(f, f.getName(), "", R.drawable.ic_file_folder, false));
                    } else if (!pickFolder) {
                        // A file of another type stays choosable, only dimmed: the type map does not know every
                        // extension (subtitles, ROMs), and the person at the remote knows what the app wants.
                        rows.add(new Row(f, f.getName(), Formatter.formatShortFileSize(this, f.length()),
                                R.drawable.ic_file_file, !FileVolumes.matches(f.getName(), mimeTypes)));
                    }
                }
            }
        }
        useFolder.setVisibility(pickFolder && dir != null ? View.VISIBLE : View.GONE);
        empty.setText(R.string.files_empty);
        empty.setVisibility(dir != null && rows.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        list.scrollToPosition(0);
        list.post(() -> {
            RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
            if (first != null) first.itemView.requestFocus();
            else if (useFolder.getVisibility() == View.VISIBLE) useFolder.requestFocus();
        });
    }

    private void open(Row row) {
        if (row.file.isDirectory()) show(row.file);
        else finishWith(row.file);
    }

    private void finishWith(File f) {
        String authority = StorageProvider.authority(this);
        String id = f.getAbsolutePath();
        Uri uri = pickFolder ? DocumentsContract.buildTreeDocumentUri(authority, id)
                : DocumentsContract.buildDocumentUri(authority, id);
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (pickFolder || openDocument) {
            flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        }
        if (pickFolder) flags |= Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        Intent data = new Intent().setData(uri).addFlags(flags);
        if (getIntent().getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)) {
            data.setClipData(ClipData.newRawUri(null, uri));
        }
        File remembered = pickFolder ? f : f.getParentFile();
        if (remembered != null) {
            prefs().edit().putString(pickFolder ? "folder" : "file", remembered.getAbsolutePath()).apply();
        }
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && current != null) {
            // Up one folder; from a volume's top, back to the volumes (or out, when there is only one).
            File parent = FileVolumes.isVolumeRoot(this, current) ? null : current.getParentFile();
            if (parent == null && FileVolumes.list(this).size() == 1) return super.onKeyDown(keyCode, event);
            show(parent);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {
        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, meta;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.fileIcon);
                name = v.findViewById(R.id.fileName);
                meta = v.findViewById(R.id.fileMeta);
                v.setOnClickListener(x -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) open(rows.get(pos));
                });
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row r = rows.get(position);
            h.icon.setImageResource(r.icon);
            h.name.setText(r.name);
            h.meta.setText(r.meta);
            h.itemView.setAlpha(r.dimmed ? 0.5f : 1f);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
