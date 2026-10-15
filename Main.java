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
    static String riskHeading = "Risk Factors";

    // left "management's" off the front, the apostrophe is an entity in some filings and not in others
    static String mdaHeading = "Discussion and Analysis of Financial Condition and Results of Operations";

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

                try {

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

                    String riskFactors = getSection(plainText, riskHeading);
                    if (riskFactors == null) {
                        System.out.println("  no item 1a in this one");
                    } else {
                        System.out.println("  item 1a is " + riskFactors.length() + " chars");
                        String preview = riskFactors;
                        if (preview.length() > 400) {
                            preview = preview.substring(0, 400);
                        }
                        System.out.println("  " + preview);
                    }

                    String mda = getSection(plainText, mdaHeading);
                    if (mda == null) {
                    System.out.println("  no md&a in this one");
                } else {
                    System.out.println("  md&a is " + mda.length() + " chars");
                        String mdaPreview = mda;
                        if (mdaPreview.length() > 400) {
                            mdaPreview = mdaPreview.substring(0, 400);
                        }
                        System.out.println("  " + mdaPreview);
                    }

                } catch (Exception e) {
                    System.out.println("one of the filings did not work, moving on: " + e);
                }
            }
        }

        ResultSet rs = st.executeQuery("SELECT ticker, form, filingDate FROM filings");
        while (rs.next()) {
            System.out.println(rs.getString("ticker") + "  " + rs.getString("form") + "  " + rs.getString("filingDate"));
        }

        System.out.println("done");
    }

    /*
    static String getItem1A(String text) {
        int start = text.indexOf("Item 1A");
        if (start == -1) {
            return null;
        }

        int end = text.indexOf("Item 1B", start);
        if (end == -1) {
            end = text.indexOf("Item 2", start);
        }
        if (end == -1) {
            end = text.length();
        }

        return text.substring(start, end);
    }

    static String getItem7(String text) {
        int start = text.indexOf("Item 7");
        if (start == -1) {
            return null;
        }

        int end = text.indexOf("Item 7A", start);
        if (end == -1) {
            end = text.indexOf("Item 8", start);
        }
        if (end == -1) {
            end = text.length();
        }

        return text.substring(start, end);
    }
    */

    // the item numbers are not the same in a 10-Q and a 10-K, so look for the heading text instead
    static String getSection(String text, String heading) {
        String lower = text.toLowerCase();
        String lowerHeading = heading.toLowerCase();

        int from = 0;
        while (true) {
            int start = lower.indexOf(lowerHeading, from);
            if (start == -1) {
                return null;
            }

            int after = start + heading.length();
            while (after < text.length() && text.charAt(after) == ' ') {
                after++;
            }

            // in the table of contents the heading is followed by a page number, the real one is followed by text
            if (after < text.length() && Character.isUpperCase(text.charAt(after))) {
                int end = nextItemHeading(text, after);
                if (end == -1) {
                    end = text.length();
                }
                return text.substring(start, end);
            }

            from = start + heading.length();
        }
    }

    // the next real heading like "Item 1B.", not a mention of item 2 in the middle of a sentence
    static int nextItemHeading(String text, int from) {
        String lower = text.toLowerCase();
        int p = from;
        while (true) {
            // some filings put the headings in caps so this has to be case insensitive too
            int at = lower.indexOf("item ", p);
            if (at == -1) {
                return -1;
            }

            int i = at + 5;
            int digits = 0;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                i++;
                digits++;
            }
            if (digits > 0 && i < text.length() && Character.isUpperCase(text.charAt(i))) {
                i++;
            }

            // a heading is not in the middle of a quote, like "Item 8. Financial Statements" over there
            boolean quoted = at > 0 && text.charAt(at - 1) == '"';

            // "refer to Part II, Item 7." is talking about another section, not starting one
            String before = text.substring(Math.max(0, at - 20), at);
            boolean partTwo = before.contains("Part ");

            // and "set forth in Item 1A. \"Risk Factors\"" has the title in quotes, a heading does not
            int afterDot = i + 1;
            while (afterDot < text.length() && text.charAt(afterDot) == ' ') {
                afterDot++;
            }
            boolean titleInQuotes = afterDot < text.length() && text.charAt(afterDot) == '"';

            if (!quoted && !partTwo && !titleInQuotes && digits > 0 && i < text.length() && text.charAt(i) == '.') {
                return at;
            }

            p = at + 5;
        }
    }

    static String stripTags(String html) {
        String noScripts = html.replaceAll("(?s)<script.*?</script>", " ");
        String noStyles = noScripts.replaceAll("(?s)<style.*?</style>", " ");
        String withoutTags = noStyles.replaceAll("<[^>]*>", " ");
        String decoded = decodeEntities(withoutTags);
        String withoutExtraSpaces = decoded.replaceAll("\\s+", " ");
        return withoutExtraSpaces.trim();
    }

    static String decodeEntities(String text) {
        String out = text.replaceAll("&#160;", " ");
        out = out.replaceAll("&nbsp;", " ");
        out = out.replaceAll("&#8217;", "'");
        out = out.replaceAll("&#8220;", "\"");
        out = out.replaceAll("&#8221;", "\"");
        out = out.replaceAll("&#39;", "'");
        out = out.replaceAll("&amp;", "&");
        out = out.replaceAll("&quot;", "\"");
        return out;
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
