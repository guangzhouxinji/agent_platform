package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import cn.xinji.agentplatform.enforcement.documenttemplate.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Render only the retained template bytes and the exact reviewed content snapshot. */
public final class DocumentDraftDocxRenderer implements ExportRenderer {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final Pattern TOKEN=Pattern.compile("\\{\\{([A-Za-z0-9_.\\[\\]]+)}}");
    private final DocumentTemplateStore templates;private final DocumentTemplateFiles files;private final ObjectMapper json;
    public DocumentDraftDocxRenderer(DocumentTemplateStore templates,DocumentTemplateFiles files,ObjectMapper json){this.templates=templates;this.files=files;this.json=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);}
    @Override public String version(){return "ENF14-OOXML-1";}
    @Override public byte[] render(String actor,Draft draft) {
        try {
            var template=templates.revision(draft.manifest().template().id(),draft.manifest().template().revision()).orElseThrow();
            byte[] original=files.read(template.fileKey());
            if(!sha(original).equals(draft.manifest().template().sha256())||!sha(original).equals(template.fileSha256()))throw new IllegalStateException("Exact template original SHA mismatch");
            var content=json.readValue(draft.contentJson(),DocumentProfileEngine.Content.class);
            if(!sha(draft.contentJson().getBytes(StandardCharsets.UTF_8)).equals(draft.contentSha256())||content.reviewBlocked()
                    ||!content.rule().id().equals(draft.manifest().rule().id())||content.rule().revision()!=draft.manifest().rule().revision()
                    ||!content.rule().templateId().equals(template.templateId())||content.rule().templateRevision()!=template.revision()
                    ||!content.rule().templateSha256().equals(template.fileSha256()))throw new IllegalArgumentException("Export content dependencies mismatch");
            var values=new TreeMap<String,Object>();var hidden=new HashSet<String>();
            for(var section:content.sections()) {
                if(section.visibility()==DocumentProfileEngine.Visibility.UNKNOWN)throw new IllegalArgumentException("Unresolved conditional section");
                if(section.visibility()==DocumentProfileEngine.Visibility.HIDDEN)hidden.add(section.id());
                else for(var field:section.fields())values.put(field.definition().id(),field.value());
            }
            var entries=unzip(original);
            if(!entries.containsKey("word/document.xml"))throw new IllegalStateException("DOCX body missing");
            for(var entry:entries.entrySet())if(entry.getKey().startsWith("word/")&&entry.getKey().endsWith(".xml"))
                entry.setValue(renderXml(entry.getValue(),values,hidden));
            try(var output=new ByteArrayOutputStream();var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {
                for(var entry:entries.entrySet()){var item=new ZipEntry(entry.getKey());item.setTime(0);zip.putNextEntry(item);zip.write(entry.getValue());zip.closeEntry();}zip.finish();return output.toByteArray();
            }
        }catch(IllegalArgumentException|IllegalStateException e){throw e;}catch(Exception e){throw new IllegalStateException("DOCX render failed; no artifact created",e);}
    }
    private byte[] renderXml(byte[] bytes,Map<String,Object> values,Set<String> hidden) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        for(var sdt:elements(doc,"sdt")){var tags=elements(sdt,"tag");if(tags.stream().anyMatch(t->hidden.contains(t.getAttributeNS(W,"val").replaceFirst("^ENF14_SECTION:",""))))sdt.getParentNode().removeChild(sdt);}
        var rowValuesByNode=new IdentityHashMap<Node,Map<String,Object>>();
        for(var row:elements(doc,"tr")) {
            var repeat=new LinkedHashSet<String>();var matcher=TOKEN.matcher(text(row));while(matcher.find())if(matcher.group(1).contains("[]"))repeat.add(matcher.group(1));
            if(repeat.isEmpty())continue;
            var roots=repeat.stream().map(id->id.substring(0,id.indexOf("[]"))).distinct().toList();if(roots.size()!=1)throw new IllegalArgumentException("Mixed repeat roots in one row");
            int size=-1;for(String id:repeat){Object raw=values.get(id);if(raw==null)raw=List.of();if(!(raw instanceof List<?> list)||size>=0&&size!=list.size())throw new IllegalArgumentException("Repeat field cardinality mismatch");size=((List<?>)raw).size();}
            for(int i=0;i<size;i++){var clone=(Element)row.cloneNode(true);var rowValues=new TreeMap<String,Object>(values);for(String id:repeat){var list=(List<?>)values.get(id);rowValues.put(id,list==null?null:list.get(i));}rowValuesByNode.put(clone,rowValues);row.getParentNode().insertBefore(clone,row);}
            row.getParentNode().removeChild(row);
        }
        // Each original placeholder is expanded exactly once. Source text never becomes a template instruction.
        replace(doc,values,rowValuesByNode);
        for(var node:elements(doc,"t"))if(node.getTextContent().contains("\n")) {
            var parts=node.getTextContent().replace("\r\n","\n").split("\n",-1);node.setTextContent(parts[0]);Node cursor=node;
            for(int i=1;i<parts.length;i++){var br=doc.createElementNS(W,"w:br");cursor.getParentNode().insertBefore(br,cursor.getNextSibling());var line=doc.createElementNS(W,"w:t");line.setAttributeNS(XMLConstants.XML_NS_URI,"xml:space","preserve");line.setTextContent(parts[i]);br.getParentNode().insertBefore(line,br.getNextSibling());cursor=line;}
        }
        var transformerFactory=TransformerFactory.newInstance();transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
        var transformer=transformerFactory.newTransformer();transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8");var out=new ByteArrayOutputStream();transformer.transform(new DOMSource(doc),new StreamResult(out));return out.toByteArray();
    }
    private void replace(Node root,Map<String,Object> defaultValues,Map<Node,Map<String,Object>> rowValues) {
        for(var paragraph:elements(root,"p")) {
            Map<String,Object> values=defaultValues;
            for(Node ancestor=paragraph;ancestor!=null;ancestor=ancestor.getParentNode())if(rowValues.containsKey(ancestor)){values=rowValues.get(ancestor);break;}
            var nodes=elements(paragraph,"t");var joined=new StringBuilder();var offsets=new ArrayList<Integer>();
            for(var node:nodes){offsets.add(joined.length());joined.append(node.getTextContent());}
            var matches=new ArrayList<int[]>();var ids=new ArrayList<String>();var matcher=TOKEN.matcher(joined);while(matcher.find()){matches.add(new int[]{matcher.start(),matcher.end()});ids.add(matcher.group(1));}
            for(int i=matches.size()-1;i>=0;i--){String id=ids.get(i);if(!values.containsKey(id))throw new IllegalArgumentException("Template field unavailable: "+id);Object raw=values.get(id);if(raw instanceof Collection<?>||raw instanceof Map<?,?>)throw new IllegalArgumentException("Unexpanded compound field");
                int start=matches.get(i)[0],end=matches.get(i)[1];String value=raw==null?"":raw.toString();
                for(int j=nodes.size()-1;j>=0;j--){int pos=offsets.get(j);String current=nodes.get(j).getTextContent();int originalEnd=j+1<offsets.size()?offsets.get(j+1):joined.length();
                    if(pos>=end||originalEnd<=start)continue;int left=Math.max(0,start-pos),right=Math.min(current.length(),end-pos);
                    nodes.get(j).setTextContent(current.substring(0,left)+(pos<=start?value:"")+current.substring(right));nodes.get(j).setAttributeNS(XMLConstants.XML_NS_URI,"xml:space","preserve");}
            }
        }
    }
    private static List<Element> elements(Node node,String local){var list=node instanceof Document d?d.getElementsByTagNameNS(W,local):((Element)node).getElementsByTagNameNS(W,local);var result=new ArrayList<Element>();for(int i=0;i<list.getLength();i++)result.add((Element)list.item(i));return result;}
    private static String text(Node root){var result=new StringBuilder();for(var t:elements(root,"t"))result.append(t.getTextContent());return result.toString();}
    private static Map<String,byte[]> unzip(byte[] bytes) throws IOException {
        var entries=new TreeMap<String,byte[]>();int total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(entry.isDirectory())continue;String name=entry.getName();
            if(name.startsWith("/")||name.contains("..")||name.contains("\\")||entries.size()>=256||entries.containsKey(name))throw new IOException("Invalid DOCX entry");
            byte[] value=zip.readNBytes(16*1024*1024+1);total+=value.length;if(value.length>16*1024*1024||total>32*1024*1024)throw new IOException("DOCX decompression limit");entries.put(name,value);}}
        return entries;
    }
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
