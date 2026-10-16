import java.io.*;
import java.util.*;

// no test library on purpose, this project has no build tool so it just runs with javac and java
public class Tests {

    static int passed = 0;
    static int failed = 0;
    static int skipped = 0;

    public static void main(String[] args) throws Exception {

        // decodeEntities, the entity code used to replace only the first one it found in a line
        same("decodes every entity in the line, not only the first",
                "a & b & c",
                Main.decodeEntities("a &amp; b &amp; c"));

        same("decodes the curly apostrophe and the nbsp",
                "don't break",
                Main.decodeEntities("don&#8217;t&#160;break"));

        // stripTags
        same("drops the script body, not just the tags",
                "hello world",
                Main.stripTags("<p>hello</p><script>var x = 1;</script><p>world</p>"));

        same("turns a pile of tags and newlines into one line",
                "one two",
                Main.stripTags("<div>\n one <b>two</b>\n</div>"));

        // this is the one that broke when the entities were decoded after the whitespace was collapsed,
        // "&#160;" is not whitespace yet so it survived the collapse and the heading never matched
        same("decodes before it collapses the spaces",
                "Risk Factors We face risks.",
                Main.stripTags("<p>Risk</p>&#160;<p>Factors</p><p>We face risks.</p>"));

        // getSection
        String toc = "Table of Contents Item 1A. Risk Factors 12 Item 1B. Unresolved Staff Comments 15 "
                + "Item 1A. Risk Factors We face a lot of risks. Item 1B. Unresolved Staff Comments 15";

        String risk = Main.getSection(toc, "Risk Factors");
        yes("skips the table of contents row", risk != null && risk.startsWith("Risk Factors We face"));
        yes("stops at the next heading", risk != null && !risk.contains("Item 1B"));

        isNull("no heading anywhere means nothing comes back",
                Main.getSection("this filing does not have that part", "Risk Factors"));

        // tsla writes its headings in all caps, a case sensitive search found nothing in it
        String caps = "ITEM 1A. RISK FACTORS We face a lot of risks. ITEM 1B. UNRESOLVED STAFF COMMENTS";
        String capsRisk = Main.getSection(caps, "Risk Factors");
        yes("finds an all caps heading", capsRisk != null && capsRisk.startsWith("RISK FACTORS"));
        yes("and ends at the all caps heading after it", capsRisk != null && !capsRisk.contains("ITEM 1B"));

        // nextItemHeading, the two traps that used to cut the mda short
        sameInt("skips a quoted item number",
                -1,
                Main.nextItemHeading("see \"Item 8. Financial Statements\" over there.", 0));

        sameInt("skips a part two reference",
                -1,
                Main.nextItemHeading("refer to Part II, Item 7. Management's Discussion for more.", 0));

        String quotedTitle = "set forth in Item 1A. \"Risk Factors\" below. Item 1B. Unresolved";
        sameInt("skips a heading whose title is in quotes",
                quotedTitle.indexOf("Item 1B"),
                Main.nextItemHeading(quotedTitle, 0));

        String plain = "text Item 3. Quantitative Disclosures";
        sameInt("still finds a plain heading",
                plain.indexOf("Item 3"),
                Main.nextItemHeading(plain, 0));

        // getArray
        ArrayList<String> forms = Main.getArray("{\"form\":[\"10-Q\",\"10-K\"]}", "form");
        yes("pulls the values out of the json",
                forms.size() == 2 && forms.get(0).equals("10-Q") && forms.get(1).equals("10-K"));
        yes("a key that is not there is just empty",
                Main.getArray("{\"form\":[\"10-Q\"]}", "nope").isEmpty());
        yes("an empty list is empty too",
                Main.getArray("{\"form\":[]}", "form").isEmpty());

        // the real filings on disk if they are still there, every one should give both sections
        File dir = new File("filings");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            skipped++;
            System.out.println("skip no filings on disk, run Main first to check the real ones");
        } else {
            int checked = 0;
            for (int i = 0; i < files.length; i++) {
                if (!files[i].getName().endsWith(".htm")) {
                    continue;
                }
                checked++;
                String text = Main.stripTags(read(files[i]));
                String name = files[i].getName();

                String realRisk = Main.getSection(text, "Risk Factors");
                yes(name + " has a risk factors section", realRisk != null && realRisk.length() > 100);

                String realMda = Main.getSection(text, "Discussion and Analysis of Financial Condition and Results of Operations");
                yes(name + " has an md&a section", realMda != null && realMda.length() > 100);
            }
            yes("checked every filing on disk", checked > 0);
        }

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed" + (skipped > 0 ? ", " + skipped + " skipped" : ""));

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("all good");
    }

    static String read(File f) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
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

    static void same(String name, String wanted, String got) {
        if (wanted.equals(got)) {
            passed++;
            System.out.println("ok   " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
            System.out.println("     wanted [" + wanted + "]");
            System.out.println("     got    [" + got + "]");
        }
    }

    static void yes(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("ok   " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void isNull(String name, Object thing) {
        if (thing == null) {
            passed++;
            System.out.println("ok   " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
            System.out.println("     got [" + thing + "]");
        }
    }

    static void sameInt(String name, int wanted, int got) {
        if (wanted == got) {
            passed++;
            System.out.println("ok   " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
            System.out.println("     wanted " + wanted + " but got " + got);
        }
    }
}
