package de.macfotocli;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class ProcessingList {
    private static final ObjectMapper mapper = new ObjectMapper();

    @JsonProperty("rootPath")
    public String rootPath;
    
    @JsonProperty("command")
    public String command;

    @JsonProperty("entries")
    public List<Entry> entries = new ArrayList<>();

    public static class Entry {
        @JsonProperty("path")
        public String path;
        @JsonProperty("status")
        public String status = "PENDING";
    }

    public static String getListFilename(String command, File directory) {
        try {
            String absPath = directory.getAbsolutePath();
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(absPath.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return ".macfotocli_" + command + "_" + sb.toString() + ".json";
        } catch (NoSuchAlgorithmException e) {
            return ".macfotocli_" + command + "_" + directory.getName() + ".json";
        }
    }

    public void save(File file) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, this);
    }

    public static ProcessingList load(File file) throws IOException {
        return mapper.readValue(file, ProcessingList.class);
    }
}
