import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Medical Book Chat - a tiny Java web server that answers questions
 * by searching the text of a medical encyclopedia you place in the /book folder.
 *
 * No external libraries needed. Needs Java 17 or newer.
 */
public class Main {

    // ---------- data kept in memory ----------
    record Chunk(String text, String source) {}

    static final List<Chunk> chunks = new ArrayList<>();
    static final List<Map<String, Integer>> termCounts = new ArrayList<>();
    static final Map<String, Integer> docFreq = new HashMap<>();
    static double avgLength = 1;

    static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "of", "in", "on", "at", "to", "for",
            "and", "or", "but", "with", "what", "which", "who", "whom", "how", "why", "when", "where", "do",
            "does", "did", "can", "could", "should", "would", "will", "it", "its", "this", "that", "these",
            "those", "about", "tell", "me", "explain", "give", "i", "my", "you", "your", "by", "from", "as",
            "have", "has", "had", "there", "their", "they", "them", "than", "then", "so", "if", "any", "some");

    // ---------- start-up ----------
    public static void main(String[] args) throws Exception {
        int port = 9090;
        Path bookFolder = Paths.get("book");
        loadBook(bookFolder);

        // try the next port automatically if this one is already used (e.g. by Steam on 8080)
        HttpServer server = null;
        for (int tries = 0; tries < 50 && server == null; tries++) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
            } catch (java.net.BindException e) {
                port++;
            }
        }
        if (server == null) {
            System.out.println("  Could not find a free port. Close some programs and try again.");
            return;
        }

        server.createContext("/", ex -> {
            if (!ex.getRequestURI().getPath().equals("/")) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            byte[] page = Files.readAllBytes(Paths.get("web", "index.html"));
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, page.length);
            ex.getResponseBody().write(page);
            ex.close();
        });

        server.createContext("/status", ex ->
                send(ex, 200, "application/json; charset=utf-8",
                        "{\"passages\":" + chunks.size() + "}"));

        server.createContext("/ask", ex -> {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                send(ex, 405, "text/plain; charset=utf-8", "Use POST");
                return;
            }
            String question = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            send(ex, 200, "application/json; charset=utf-8", answer(question));
        });

        server.start();
        System.out.println();
        System.out.println("  Medical Book Chat is running.");
        System.out.println("  Open this in your browser:  http://localhost:" + port);
        System.out.println("  Passages loaded from book/: " + chunks.size());
        System.out.println();
    }

    // ---------- reading the book ----------
    static void loadBook(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) Files.createDirectories(folder);

        List<Path> files;
        try (Stream<Path> s = Files.list(folder)) {
            files = s.filter(p -> p.toString().toLowerCase().endsWith(".txt") && !p.getFileName().toString().startsWith("PUT_")).sorted().toList();
        }
        if (files.isEmpty()) {
            System.out.println("  !! No .txt files found in the 'book' folder yet.");
        }

        for (Path file : files) {
            String all = Files.readString(file, StandardCharsets.UTF_8);
            String[] paragraphs = all.split("\\R\\s*\\R");   // split on blank lines
            StringBuilder current = new StringBuilder();
            int words = 0;

            for (String para : paragraphs) {
                String p = para.replaceAll("\\s+", " ").trim();
                if (p.isEmpty() || p.matches("^[0-9 ]+$")) continue;      // skip page numbers
                p = p.replaceAll("^[0-9]{1,4} (?=[A-Z])", "");               // "381 Asthma" -> "Asthma"
                current.append(p).append("\n\n");
                words += p.split(" ").length;
                if (words >= 140) {               // roughly one passage = 140+ words
                    addChunk(current.toString().trim(), file.getFileName().toString());
                    current.setLength(0);
                    words = 0;
                }
            }
            if (current.length() > 0) addChunk(current.toString().trim(), file.getFileName().toString());
        }

        double total = 0;
        for (Map<String, Integer> tc : termCounts) total += tc.values().stream().mapToInt(i -> i).sum();
        avgLength = chunks.isEmpty() ? 1 : total / chunks.size();
    }

    static void addChunk(String text, String source) {
        Map<String, Integer> counts = new HashMap<>();
        for (String t : tokenize(text)) counts.merge(t, 1, Integer::sum);
        for (String t : counts.keySet()) docFreq.merge(t, 1, Integer::sum);
        chunks.add(new Chunk(text, source));
        termCounts.add(counts);
    }

    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() < 2 || STOP_WORDS.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    // very light stemming so "diabetic" / "diabetes", "kidneys" / "kidney" match more often
    static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && w.endsWith("es")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    // ---------- answering ----------
    static String answer(String question) {
        String q = question.toLowerCase().trim();

        if (q.isEmpty()) return reply("Type a question and I will look it up for you.", List.of());
        if (q.matches("^(hi|hello|hey|hii|namaste|good (morning|evening|afternoon))\\W*$"))
            return reply("Hello! I'm your medical reference helper. Ask me about any disease, "
                    + "symptom, treatment or medicine and I will look through the book for you.", List.of());
        if (chunks.isEmpty())
            return reply("I don't have any book text yet. Put your book as a .txt file inside the "
                    + "'book' folder, then restart the program.", List.of());

        List<String> terms = tokenize(question);
        if (terms.isEmpty())
            return reply("Could you add a little more detail? Try a disease or topic name, "
                    + "like \"What is asthma?\"", List.of());

        // BM25 ranking
        double k1 = 1.5, b = 0.75, n = chunks.size();
        double[] scores = new double[chunks.size()];
        for (String t : new HashSet<>(terms)) {
            Integer df = docFreq.get(t);
            if (df == null) continue;
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (int i = 0; i < chunks.size(); i++) {
                Integer tf = termCounts.get(i).get(t);
                if (tf == null) continue;
                double len = termCounts.get(i).values().stream().mapToInt(x -> x).sum();
                scores[i] += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * len / avgLength));
            }
        }

        // boost passages that open an encyclopedia entry (heading + "Definition" section)
        for (int i = 0; i < chunks.size(); i++) {
            if (scores[i] <= 0) continue;
            String t = chunks.get(i).text();
            String head = t.substring(0, Math.min(80, t.length())).toLowerCase();
            for (String term : new HashSet<>(terms)) if (head.contains(term)) { scores[i] *= 1.3; break; }
            if (t.contains("Definition")) scores[i] *= 1.4;
            if (t.contains("ORGANIZATIONS") || t.contains("PERIODICALS") || t.contains("BOOKS")) scores[i] *= 0.4; // reference lists
        }

        Integer[] order = new Integer[chunks.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(scores[y], scores[x]));

        if (scores[order[0]] <= 0)
            return reply("Sorry, I couldn't find anything about that in the book. "
                    + "Try different words or a more specific term.", List.of());

        // top 3 passages
        List<String> passages = new ArrayList<>();
        for (int i = 0; i < Math.min(3, order.length); i++) {
            if (scores[order[i]] <= 0) break;
            Chunk c = chunks.get(order[i]);
            passages.add("{\"text\":\"" + esc(c.text()) + "\",\"source\":\"" + esc(c.source()) + "\"}");
        }

        // short answer = best sentences from the best passage
        String summary = bestSentences(chunks.get(order[0]).text(), new HashSet<>(terms), 3);
        String intro = "Here is what the book says:\n\n" + summary;

        StringBuilder termJson = new StringBuilder();
        for (String t : new LinkedHashSet<>(terms)) {
            if (termJson.length() > 0) termJson.append(",");
            termJson.append("\"").append(esc(t)).append("\"");
        }

        return "{\"answer\":\"" + esc(intro) + "\",\"terms\":[" + termJson + "],\"sources\":["
                + String.join(",", passages) + "]}";
    }

    static String bestSentences(String text, Set<String> terms, int max) {
        String[] sentences = text.replaceAll("\\s+", " ").split("(?<=[.!?])\\s+");
        double[] sc = new double[sentences.length];
        for (int i = 0; i < sentences.length; i++) {
            if (sentences[i].split(" ").length < 7) continue;   // skip captions / headings
            Set<String> toks = new HashSet<>(tokenize(sentences[i]));
            for (String t : terms) if (toks.contains(t)) sc[i] += 1;
            if (i == 0) sc[i] += 0.5;   // opening sentence is usually the definition
        }
        Integer[] idx = new Integer[sentences.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(sc[b], sc[a]));

        List<Integer> chosen = Arrays.stream(idx).limit(max).filter(i -> sc[i] > 0)
                .sorted().collect(Collectors.toList());
        if (chosen.isEmpty()) chosen.add(0);
        return chosen.stream().map(i -> sentences[i]).collect(Collectors.joining(" "));
    }

    // ---------- small helpers ----------
    static String reply(String text, List<String> sources) {
        return "{\"answer\":\"" + esc(text) + "\",\"terms\":[],\"sources\":[" + String.join(",", sources) + "]}";
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append(' ');
                default -> sb.append(c < 32 ? ' ' : c);
            }
        }
        return sb.toString();
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
