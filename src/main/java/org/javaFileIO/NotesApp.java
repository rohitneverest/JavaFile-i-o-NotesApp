package org.javaFileIO;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class NotesApp {

    private static final String NOTES_FILE = "notes.db";
    private static final int XOR_KEY = 13; // small obfuscation key (deterministic)
    private static final String SEP = "|||";
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static Scanner sc = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("Welcome to Your Unique NotesApp ✨");
        while (true) {
            System.out.println("\nChoose: 1-add  2-viewAll  3-searchTitle  4-delete  5-backup  6-restore  7-exit");
            System.out.print("option> ");
            String opt = sc.nextLine().trim();
            try {
                switch (opt) {
                    case "1": addNote(); break;
                    case "2": viewAll(); break;
                    case "3": searchByTitle(); break;
                    case "4": deleteNote(); break;
                    case "5": backup(); break;
                    case "6": restore(); break;
                    case "7": System.out.println("Bye!"); return;
                    default: System.out.println("Unknown option.");
                }
            } catch (IOException e) {
                System.out.println("I/O error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // Add a new note
    private static void addNote() throws IOException {
        System.out.print("Title: ");
        String title = sc.nextLine().trim();
        System.out.print("Category (eg. personal, study): ");
        String cat = sc.nextLine().trim();
        System.out.println("Content (end with a single line: .done ):");
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line = sc.nextLine();
            if (line.equals(".done")) break;
            sb.append(line).append("\n");
        }
        String content = sb.toString().trim();
        String id = UUID.randomUUID().toString();
        String timestamp = LocalDateTime.now().format(TF);
        String serialized = id + SEP + escape(content, title, cat) + SEP + timestamp;
        // Note: we placed title+category inside escape to keep parsing simple
        // Build final line as: id ||| title ||| category ||| content ||| timestamp
        String fullLine = id + SEP + title + SEP + cat + SEP + escapeNewlines(content) + SEP + timestamp;

        String encrypted = xor(fullLine, XOR_KEY);
        // append
        try (FileWriter fw = new FileWriter(NOTES_FILE, true)) {
            fw.write(encrypted + System.lineSeparator());
        }
        System.out.println("Saved note (" + title + ") at " + timestamp);
    }

    // View all notes
    private static void viewAll() throws IOException {
        List<Note> notes = readAll();
        if (notes.isEmpty()) {
            System.out.println("No notes yet.");
            return;
        }
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            System.out.printf("[%d] %s | %s | %s\n", i+1, n.title, n.category, n.timestamp);
            System.out.println("----");
            System.out.println(n.content);
            System.out.println("====\n");
        }
    }

    // Search by title substring
    private static void searchByTitle() throws IOException {
        System.out.print("Enter title substring to search: ");
        String q = sc.nextLine().trim().toLowerCase();
        List<Note> notes = readAll();
        boolean found = false;
        for (Note n : notes) {
            if (n.title.toLowerCase().contains(q)) {
                System.out.printf("%s | %s | %s\n", n.title, n.category, n.timestamp);
                System.out.println(n.content);
                System.out.println("----");
                found = true;
            }
        }
        if (!found) System.out.println("No matching notes.");
    }

    // Delete a note by index shown in viewAll
    private static void deleteNote() throws IOException {
        List<Note> notes = readAll();
        if (notes.isEmpty()) {
            System.out.println("No notes to delete.");
            return;
        }
        for (int i = 0; i < notes.size(); i++) {
            System.out.printf("[%d] %s | %s | %s\n", i+1, notes.get(i).title, notes.get(i).category, notes.get(i).timestamp);
        }
        System.out.print("Enter number to delete: ");
        String s = sc.nextLine().trim();
        try {
            int idx = Integer.parseInt(s) - 1;
            if (idx < 0 || idx >= notes.size()) {
                System.out.println("Invalid index.");
                return;
            }
            Note removed = notes.remove(idx);
            // rewrite file (overwrite)
            try (FileWriter fw = new FileWriter(NOTES_FILE, false)) {
                for (Note n : notes) {
                    String line = n.id + SEP + n.title + SEP + n.category + SEP + escapeNewlines(n.content) + SEP + n.timestamp;
                    fw.write(xor(line, XOR_KEY) + System.lineSeparator());
                }
            }
            System.out.println("Deleted note: " + removed.title);
        } catch (NumberFormatException e) {
            System.out.println("Not a valid number.");
        }
    }

    // Simple backup copies notes.db to notes_backup.db
    private static void backup() throws IOException {
        File f = new File(NOTES_FILE);
        if (!f.exists()) {
            System.out.println("No notes file to backup.");
            return;
        }
        try (FileReader fr = new FileReader(NOTES_FILE);
             FileWriter fw = new FileWriter("notes_backup.db", false)) {
            int ch;
            while ((ch = fr.read()) != -1) fw.write(ch);
        }
        System.out.println("Backup written to notes_backup.db");
    }

    // Restore from notes_backup.db (overwrite)
    private static void restore() throws IOException {
        File fb = new File("notes_backup.db");
        if (!fb.exists()) {
            System.out.println("No backup found.");
            return;
        }
        try (FileReader fr = new FileReader(fb);
             FileWriter fw = new FileWriter(NOTES_FILE, false)) {
            int ch;
            while ((ch = fr.read()) != -1) fw.write(ch);
        }
        System.out.println("Restored from notes_backup.db");
    }

    // Read all notes from file and return List<Note>
    private static List<Note> readAll() throws IOException {
        List<Note> out = new ArrayList<>();
        File f = new File(NOTES_FILE);
        if (!f.exists()) return out;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String encLine;
            while ((encLine = br.readLine()) != null) {
                if (encLine.trim().isEmpty()) continue;
                String line = xor(encLine, XOR_KEY);
                // parse fields
                String[] parts = line.split("\\Q" + SEP + "\\E", -1);
                if (parts.length < 5) continue; // skip malformed
                String id = parts[0];
                String title = parts[1];
                String cat = parts[2];
                String content = unescapeNewlines(parts[3]);
                String ts = parts[4];
                out.add(new Note(id, title, cat, content, ts));
            }
        }
        return out;
    }

    // Simple XOR obfuscation (not secure encryption)
    private static String xor(String s, int key) {
        char[] arr = s.toCharArray();
        for (int i = 0; i < arr.length; i++) {
            arr[i] = (char) (arr[i] ^ key);
        }
        return new String(arr);
    }

    // Escape newlines so we can store multi-line content on one file line
    private static String escapeNewlines(String s) {
        return s.replace("\n", "\\n");
    }

    private static String unescapeNewlines(String s) {
        return s.replace("\\n", "\n");
    }

    // trivial placeholder if you want to escape other fields later
    private static String escape(String... parts) {
        return String.join(SEP, parts);
    }

    // Note container
    private static class Note {
        String id, title, category, content, timestamp;
        Note(String id, String title, String category, String content, String timestamp) {
            this.id = id; this.title = title; this.category = category; this.content = content; this.timestamp = timestamp;
        }
    }
}
