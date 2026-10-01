package nl.paree.climbpro.data.medical;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * JSON-file persistence for the medical ID (issue #230): getFilesDir()/medical_id.json.
 * Writes are atomic (temp file + rename). A missing or unreadable file loads as empty.
 */
public final class MedicalIdRepository {

    private static final String TAG = "MedicalIdRepository";
    static final String FILE = "medical_id.json";

    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();

    public MedicalIdRepository(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    public MedicalId load() {
        if (!file.exists()) return new MedicalId();
        try (FileInputStream in = new FileInputStream(file)) {
            MedicalId id = mapper.readValue(in, MedicalId.class);
            return id != null ? id : new MedicalId();
        } catch (IOException e) {
            Log.e(TAG, "Failed to load medical ID", e);
            return new MedicalId();
        }
    }

    public synchronized void save(MedicalId id) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            mapper.writeValue(out, id);
        }
        if (!tmp.renameTo(file) && (!file.delete() || !tmp.renameTo(file))) {
            throw new IOException("Could not replace " + file);
        }
    }
}
