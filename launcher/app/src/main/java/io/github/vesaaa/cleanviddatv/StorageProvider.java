package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * The TV's storage (internal and USB drives) as documents, for FilePickerActivity. China-market Hisense / Vidda
 * firmware ships no DocumentsUI, so nothing answers an app's "pick a file" or "pick a folder" request; the picker
 * answers it with URIs of this provider, granted to the asking app. Only the system holds MANAGE_DOCUMENTS, so apps
 * reach these documents through those grants alone. Needs all-files access (install-vidda.sh grants it).
 *
 * Document ids are absolute paths, accepted only inside one of the volumes FileVolumes lists.
 */
public class StorageProvider extends DocumentsProvider {

    static String authority(Context ctx) {
        return ctx.getPackageName() + ".documents";
    }

    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        for (FileVolumes.Volume v : FileVolumes.list(getContext())) {
            MatrixCursor.RowBuilder row = c.newRow();
            row.add(Root.COLUMN_ROOT_ID, v.dir.getAbsolutePath());
            row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_LOCAL_ONLY);
            row.add(Root.COLUMN_TITLE, v.title);
            row.add(Root.COLUMN_DOCUMENT_ID, v.dir.getAbsolutePath());
            row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
            row.add(Root.COLUMN_AVAILABLE_BYTES, v.dir.getUsableSpace());
        }
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addRow(c, file(documentId));
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        File parent = file(parentDocumentId);
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] children = parent.listFiles();
        if (children != null) {
            Arrays.sort(children, FileVolumes.ORDER);
            for (File child : children) addRow(c, child);
        }
        c.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildChildDocumentsUri(authority(getContext()), parentDocumentId));
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File f = file(documentId);
        if (f.isDirectory()) throw new FileNotFoundException("a folder: " + documentId);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = file(parentDocumentId);
        String name = FileVolumes.safeName(displayName);
        if (name == null) throw new FileNotFoundException("bad name: " + displayName);
        boolean folder = Document.MIME_TYPE_DIR.equals(mimeType);
        File target = new File(parent, name);
        // Like DocumentsUI: a name that is taken gets " (1)", " (2)" before its extension.
        for (int n = 1; target.exists(); n++) target = new File(parent, FileVolumes.numbered(name, n, folder));
        try {
            boolean made = folder ? target.mkdir() : target.createNewFile();
            if (!made) throw new FileNotFoundException("cannot create " + target);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        changed(parentDocumentId);
        return target.getAbsolutePath();
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File f = file(documentId);
        if (FileVolumes.isVolumeRoot(getContext(), f) || !FileVolumes.deleteTree(f)) {
            throw new FileNotFoundException("cannot delete " + documentId);
        }
        File parent = f.getParentFile();
        if (parent != null) changed(parent.getAbsolutePath());
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File f = file(documentId);
        String name = FileVolumes.safeName(displayName);
        File target = name == null ? null : new File(f.getParentFile(), name);
        if (target == null || FileVolumes.isVolumeRoot(getContext(), f) || target.exists() || !f.renameTo(target)) {
            throw new FileNotFoundException("cannot rename " + documentId);
        }
        changed(f.getParent());
        return target.getAbsolutePath();
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return FileVolumes.isInside(documentId, parentDocumentId);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return mimeType(file(documentId));
    }

    /** The file for a document id, refused outside the volumes. */
    private File file(String documentId) throws FileNotFoundException {
        File f = FileVolumes.resolve(getContext(), documentId);
        if (f == null || !f.exists()) throw new FileNotFoundException(documentId);
        return f;
    }

    private void changed(String parentDocumentId) {
        Context ctx = getContext();
        ctx.getContentResolver().notifyChange(
                DocumentsContract.buildChildDocumentsUri(authority(ctx), parentDocumentId), null, false);
    }

    private static void addRow(MatrixCursor c, File f) {
        boolean dir = f.isDirectory();
        int flags = 0;
        if (f.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
            flags |= dir ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE;
        }
        MatrixCursor.RowBuilder row = c.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, f.getAbsolutePath());
        row.add(Document.COLUMN_DISPLAY_NAME, f.getName());
        row.add(Document.COLUMN_MIME_TYPE, mimeType(f));
        row.add(Document.COLUMN_LAST_MODIFIED, f.lastModified());
        row.add(Document.COLUMN_SIZE, dir ? null : f.length());
        row.add(Document.COLUMN_FLAGS, flags);
    }

    static String mimeType(File f) {
        if (f.isDirectory()) return Document.MIME_TYPE_DIR;
        return mimeTypeOf(f.getName());
    }

    static String mimeTypeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type != null ? type : "application/octet-stream";
    }
}
