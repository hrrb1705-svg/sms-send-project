package ir.example.smssend;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class XlsxSmsWorkbook {

    public static final Object FILE_LOCK = new Object();
    public static final int COLUMN_COUNT = 14;
    public static final int COL_USERNAME = 0;
    public static final int COL_PHONE = 1;
    public static final int COL_MESSAGE = 2;
    public static final int COL_REPLY = 3;
    public static final int COL_STATUS = 4;
    public static final String SENT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final java.util.regex.Pattern SENT_DATE_PATTERN =
            java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$");

    public List<String[]> read(Context context, Uri uri) throws Exception {
        byte[] data = readAll(context.getContentResolver(), uri);
        byte[] sharedStringsBytes = null;
        byte[] sheetBytes = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("xl/sharedStrings.xml".equals(entry.getName())) {
                    sharedStringsBytes = readAll(zis);
                } else if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    sheetBytes = readAll(zis);
                }
            }
        }
        List<String> sharedStrings = sharedStringsBytes == null ? new ArrayList<>() : parseSharedStrings(sharedStringsBytes);
        List<String[]> rows = new ArrayList<>();
        if (sheetBytes == null) {
            return rows;
        }
        Document sheetDoc = parseXml(sheetBytes);
        NodeList rowNodes = sheetDoc.getElementsByTagNameNS("*", "row");
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element row = (Element) rowNodes.item(i);
            String[] values = new String[COLUMN_COUNT];
            Arrays.fill(values, "");
            NodeList cellNodes = row.getElementsByTagNameNS("*", "c");
            for (int j = 0; j < cellNodes.getLength(); j++) {
                Element cell = (Element) cellNodes.item(j);
                String ref = cell.getAttribute("r");
                int col = columnIndex(ref);
                if (col < 0 || col >= COLUMN_COUNT) {
                    continue;
                }
                values[col] = cellValue(cell, sharedStrings);
            }
            rows.add(values);
        }
        return rows;
    }

    public void write(Context context, Uri uri, List<String[]> rows) throws Exception {
        byte[] original = readAll(context.getContentResolver(), uri);
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(original))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), readAll(zis));
            }
        }

        byte[] sheetBytes = entries.get("xl/worksheets/sheet1.xml");
        if (sheetBytes == null || !entries.containsKey("[Content_Types].xml")) {
            // Original archive missing or unreadable structure: fall back to a fresh minimal workbook.
            writeBytes(context, uri, buildWorkbook(rows));
            return;
        }

        Document doc = parseXml(sheetBytes);
        patchCells(doc, rows);
        entries.put("xl/worksheets/sheet1.xml", serializeXml(doc));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        writeBytes(context, uri, buffer.toByteArray());
    }

    private void writeBytes(Context context, Uri uri, byte[] bytes) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        try (OutputStream out = resolver.openOutputStream(uri, "rwt")) {
            if (out == null) {
                throw new IllegalStateException("Unable to open file for writing");
            }
            out.write(bytes);
            out.flush();
        }
    }

    private void patchCells(Document doc, List<String[]> rows) {
        if (rows == null) {
            return;
        }
        Element root = doc.getDocumentElement();
        String ns = root.getNamespaceURI();
        NodeList rowNodes = root.getElementsByTagNameNS("*", "row");
        int count = Math.min(rows.size(), rowNodes.getLength());
        for (int i = 0; i < count; i++) {
            Element rowEl = (Element) rowNodes.item(i);
            String[] row = rows.get(i);
            int rowNum = i + 1;
            try {
                String rAttr = rowEl.getAttribute("r");
                if (rAttr != null && !rAttr.trim().isEmpty()) {
                    rowNum = Integer.parseInt(rAttr.trim());
                }
            } catch (Exception ignored) {
            }
            String message = row != null && row.length > COL_MESSAGE && row[COL_MESSAGE] != null ? row[COL_MESSAGE] : "";
            String reply = row != null && row.length > COL_REPLY && row[COL_REPLY] != null ? row[COL_REPLY] : "";
            String status = row != null && row.length > COL_STATUS && row[COL_STATUS] != null ? row[COL_STATUS] : "";
            setCellValue(doc, ns, rowEl, rowNum, COL_MESSAGE, message);
            setCellValue(doc, ns, rowEl, rowNum, COL_REPLY, reply);
            setCellValue(doc, ns, rowEl, rowNum, COL_STATUS, status);
        }
    }

    private void setCellValue(Document doc, String ns, Element rowEl, int rowNum, int colIndex, String value) {
        String cellRef = colName(colIndex) + rowNum;
        NodeList cellNodes = rowEl.getElementsByTagNameNS("*", "c");
        Element existingCell = null;
        Element insertBeforeCell = null;
        for (int i = 0; i < cellNodes.getLength(); i++) {
            Element c = (Element) cellNodes.item(i);
            int cCol = columnIndex(c.getAttribute("r"));
            if (cCol == colIndex) {
                existingCell = c;
                break;
            } else if (cCol > colIndex && insertBeforeCell == null) {
                insertBeforeCell = c;
            }
        }
        if (existingCell == null) {
            existingCell = doc.createElementNS(ns, "c");
            existingCell.setAttribute("r", cellRef);
            if (insertBeforeCell != null) {
                rowEl.insertBefore(existingCell, insertBeforeCell);
            } else {
                rowEl.appendChild(existingCell);
            }
        }
        while (existingCell.hasChildNodes()) {
            existingCell.removeChild(existingCell.getFirstChild());
        }
        if (value == null || value.isEmpty()) {
            existingCell.removeAttribute("t");
            return;
        }
        existingCell.setAttribute("t", "inlineStr");
        Element is = doc.createElementNS(ns, "is");
        Element t = doc.createElementNS(ns, "t");
        if (needsPreserve(value)) {
            t.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
        }
        t.setTextContent(value);
        is.appendChild(t);
        existingCell.appendChild(is);
    }

    private byte[] serializeXml(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    private byte[] buildWorkbook(List<String[]> rows) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putTextEntry(zos, "[Content_Types].xml", contentTypes());
            putTextEntry(zos, "_rels/.rels", rootRels());
            putTextEntry(zos, "xl/workbook.xml", workbookXml());
            putTextEntry(zos, "xl/_rels/workbook.xml.rels", workbookRels());
            putTextEntry(zos, "xl/worksheets/sheet1.xml", sheetXml(rows));
            putTextEntry(zos, "docProps/core.xml", coreProps());
            putTextEntry(zos, "docProps/app.xml", appProps());
        }
        return baos.toByteArray();
    }

    private String sheetXml(List<String[]> rows) {
        int maxRow = rows == null ? 0 : rows.size();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        sb.append("<dimension ref=\"A1:").append(colName(COLUMN_COUNT - 1)).append(Math.max(1, maxRow)).append("\"/>");
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>");
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
        sb.append("<sheetData>");
        for (int i = 0; i < maxRow; i++) {
            String[] row = rows.get(i);
            sb.append("<row r=\"").append(i + 1).append("\">");
            for (int c = 0; c < COLUMN_COUNT; c++) {
                String value = row != null && c < row.length && row[c] != null ? row[c] : "";
                sb.append("<c r=\"").append(colName(c)).append(i + 1).append("\" t=\"inlineStr\"><is><t");
                if (needsPreserve(value)) {
                    sb.append(" xml:space=\"preserve\"");
                }
                sb.append(">").append(escapeXml(value)).append("</t></is></c>");
            }
            sb.append("</row>");
        }
        if (maxRow == 0) {
            String[] headers = {"نام کاربر", "شماره موبایل", "متن پیام", "پاسخ دریافت کننده", "خطای احتمالی",
                    "عبارت ۱", "عبارت ۲", "عبارت ۳", "عبارت ۴", "عبارت ۵", "عبارت ۶", "عبارت ۷", "عبارت ۸", "عبارت ۹"};
            sb.append("<row r=\"1\">");
            for (int c = 0; c < COLUMN_COUNT; c++) {
                sb.append("<c r=\"").append(colName(c)).append("1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escapeXml(headers[c])).append("</t></is></c>");
            }
            sb.append("</row>");
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private List<String> parseSharedStrings(byte[] bytes) throws Exception {
        List<String> strings = new ArrayList<>();
        Document doc = parseXml(bytes);
        NodeList siNodes = doc.getElementsByTagNameNS("*", "si");
        for (int i = 0; i < siNodes.getLength(); i++) {
            Node node = siNodes.item(i);
            strings.add(extractText(node));
        }
        return strings;
    }

    private String cellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            NodeList tNodes = cell.getElementsByTagNameNS("*", "t");
            if (tNodes.getLength() > 0) {
                return tNodes.item(0).getTextContent();
            }
            return "";
        }
        NodeList vNodes = cell.getElementsByTagNameNS("*", "v");
        if (vNodes.getLength() == 0) {
            NodeList isNodes = cell.getElementsByTagNameNS("*", "is");
            if (isNodes.getLength() > 0) {
                return extractText(isNodes.item(0));
            }
            return "";
        }
        String value = vNodes.item(0).getTextContent();
        if ("s".equals(type)) {
            try {
                int idx = Integer.parseInt(value.trim());
                if (idx >= 0 && idx < sharedStrings.size()) {
                    return sharedStrings.get(idx);
                }
            } catch (Exception ignored) {
            }
            return "";
        }
        return value == null ? "" : value;
    }

    private int columnIndex(String ref) {
        if (ref == null || ref.isEmpty()) return -1;
        int i = 0;
        int col = 0;
        while (i < ref.length()) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                col = col * 26 + (ch - 'A' + 1);
            } else if (ch >= 'a' && ch <= 'z') {
                col = col * 26 + (ch - 'a' + 1);
            } else {
                break;
            }
            i++;
        }
        return col - 1;
    }

    private String colName(int index) {
        int n = index + 1;
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private String extractText(Node node) {
        StringBuilder sb = new StringBuilder();
        if (node == null) return "";
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE || child.getNodeType() == Node.TEXT_NODE) {
                if (child.getTextContent() != null) {
                    sb.append(child.getTextContent());
                }
            }
        }
        return sb.toString();
    }

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return builder.parse(in);
        }
    }

    private byte[] readAll(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("Unable to open input stream");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private void putTextEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
    }

    private String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
                + "</Types>";
    }

    private String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                + "</Relationships>";
    }

    private String workbookXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>";
    }

    private String workbookRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>";
    }

    private String coreProps() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>sms-send</dc:title><dc:creator>GapGPT</dc:creator><cp:lastModifiedBy>GapGPT</cp:lastModifiedBy></cp:coreProperties>";
    }

    private String appProps() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
                + "<Application>Android</Application><DocSecurity>0</DocSecurity><ScaleCrop>false</ScaleCrop>"
                + "<HeadingPairs><vt:vector size=\"2\" baseType=\"variant\"><vt:variant><vt:lpstr>Worksheets</vt:lpstr></vt:variant><vt:variant><vt:i4>1</vt:i4></vt:variant></vt:vector></HeadingPairs>"
                + "<TitlesOfParts><vt:vector size=\"1\" baseType=\"lpstr\"><vt:lpstr>Sheet1</vt:lpstr></vt:vector></TitlesOfParts>"
                + "</Properties>";
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private boolean needsPreserve(String s) {
        return s != null && (!s.isEmpty() && (Character.isWhitespace(s.charAt(0)) || Character.isWhitespace(s.charAt(s.length() - 1))));
    }
}
