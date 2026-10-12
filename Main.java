import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

public class Main {

    // SEC blocks you without a real user agent and a contact email
    static final String USER_AGENT = "Tanay Chatwani tanaychatwani@gmail.com";

    static HashMap<String, String> cikNumbers = new HashMap<String, String>();

    static String[] tickers = { "AAPL", "TSLA", "NVDA" };
    static String[] formsWeWant = { "10-Q", "10-K" };
    static int filingsPerCompany = 3;
    static String outDir = "filings";

    public static void main(String[] args) throws Exception {

        cikNumbers.put("AAPL", "0000320193");
        cikNumbers.put("TSLA", "0001318605");
        cikNumbers.put("NVDA", "0001045810");

        String olderFilingsNote = "only looking at the recent ones for now";

        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:filings.db");
        Statement st = conn.createStatement();
        st.execute("CREATE TABLE IF NOT EXISTS filings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ticker TEXT, cik TEXT, form TEXT, filingDate TEXT, reportDate TEXT, " +
                "accession TEXT, filePath TEXT, downloadedAt TEXT)");

        new File(outDir).mkdirs();

        System.out.println("made it to the start of the loop");

        for (String ticker : tickers) {

            String cik = cikNumbers.get(ticker);
            String json = get("https://data.sec.gov/submissions/CIK" + cik + ".json");

            ArrayList<String> formList = getArray(json, "form");
            ArrayList<String> filingDates = getArray(json, "filingDate");
            ArrayList<String> reportDates = getArray(json, "reportDate");
            ArrayList<String> accessions = getArray(json, "accessionNumber");
            ArrayList<String> documents = getArray(json, "primaryDocument");

            ArrayList<Integer> chosen = new ArrayList<Integer>();
            for (int i = 0; i < formList.size(); i++) {
                boolean wanted = false;
                for (int f = 0; f < formsWeWant.length; f++) {
                    if (formList.get(i).equals(formsWeWant[f])) {
                        wanted = true;
                    }
                }
                if (wanted) {
                    chosen.add(i);
                }
                if (chosen.size() >= filingsPerCompany) {
                    break;
                }
            }

            System.out.println("found " + chosen.size() + " filings for " + ticker);

            for (int c = 0; c < chosen.size(); c++) {

                int i = chosen.get(c);
                String accession = accessions.get(i);
                String noDashes = accession.replace("-", "");

                // edgar file paths want the cik with no leading zeros
                String url = "https://www.sec.gov/Archives/edgar/data/" + cik.replaceFirst("^0+", "")
                        + "/" + noDashes + "/" + documents.get(i);

                String filePath = outDir + "/" + ticker + "-" + noDashes + ".htm";

                Thread.sleep(200);

                String html = get(url);
                FileWriter fw = new FileWriter(filePath);
                fw.write(html);
                fw.close();

                st.execute("INSERT INTO filings (ticker, cik, form, filingDate, reportDate, accession, filePath, downloadedAt) VALUES ('"
                        + ticker + "', '" + cik + "', '" + formList.get(i) + "', '" + filingDates.get(i) + "', '"
                        + reportDates.get(i) + "', '" + accession + "', '" + filePath + "', '" + new java.util.Date() + "')");

                System.out.println(filePath + " - " + html.length() + " chars");

                String plainText = stripTags(html);
                System.out.println("  plain text is " + plainText.length() + " chars");
            }
        }

        ResultSet rs = st.executeQuery("SELECT ticker, form, filingDate FROM filings");
        while (rs.next()) {
            System.out.println(rs.getString("ticker") + "  " + rs.getString("form") + "  " + rs.getString("filingDate"));
        }

        System.out.println("done");
    }

    static String stripTags(String html) {
        String withoutTags = html.replaceAll("<[^>]*>", " ");
        String withoutExtraSpaces = withoutTags.replaceAll("\\s+", " ");
        return withoutExtraSpaces.trim();
    }

    static String get(String url) throws Exception {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestProperty("User-Agent", USER_AGENT);

        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line = in.readLine();
        while (line != null) {
            sb.append(line);
            sb.append("\n");
            line = in.readLine();
        }
        in.close();

        return sb.toString();
    }

    static ArrayList<String> getArray(String json, String key) {
        ArrayList<String> values = new ArrayList<String>();

        int start = json.indexOf("\"" + key + "\":[");
        if (start == -1) {
            return values;
        }
        start = json.indexOf("[", start) + 1;
        int end = json.indexOf("]", start);

        String body = json.substring(start, end).trim();
        if (body.length() == 0) {
            return values;
        }

        String[] parts = body.split("\",\"");
        for (int i = 0; i < parts.length; i++) {
            values.add(parts[i].replace("\"", ""));
        }

        return values;
    }
}
