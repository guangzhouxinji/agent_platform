package cn.xinji.agentplatform.enforcement.documentdraft;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.io.ByteArrayOutputStream;

/** Configuration-driven OOXML template asset; placeholders are not case facts or official approval. */
public final class DocumentProfileTemplateAsset {
    public byte[] create(DocumentTemplateProfile profile) {
        var definitions=new HashMap<String,DocumentTemplateProfile.FieldDefinition>();profile.fields().forEach(f->definitions.put(f.id(),f));
        var body=new StringBuilder(paragraph(profile.title(),"Title"));
        body.append(paragraph("项目内置辅助草稿模板  "+profile.classification(),"Normal"));
        for(var section:profile.sections()) {
            body.append("<w:sdt><w:sdtPr><w:tag w:val='ENF14_SECTION:").append(escape(section.id())).append("'/></w:sdtPr><w:sdtContent>");
            body.append(paragraph(section.title(),"Heading1"));
            var repeat=new LinkedHashMap<String,List<DocumentTemplateProfile.FieldDefinition>>();
            for(String id:section.fields()) {
                var field=definitions.get(id);
                if(field.repeated())repeat.computeIfAbsent(id.substring(0,id.indexOf("[]")),x->new ArrayList<>()).add(field);
                else body.append(paragraph(field.label()+"  {{"+id+"}}","Normal"));
            }
            for(var group:repeat.values()) {
                body.append("<w:tbl><w:tblPr><w:tblW w:w='0' w:type='auto'/></w:tblPr><w:tr>");
                for(var field:group)body.append("<w:tc>").append(paragraph(field.label()+"  {{"+field.id()+"}}","Normal")).append("</w:tc>");
                body.append("</w:tr></w:tbl><w:p/>");
            }
            body.append("</w:sdtContent></w:sdt>");
        }
        body.append("<w:sectPr><w:pgSz w:w='11906' w:h='16838'/><w:pgMar w:top='1440' w:right='1440' w:bottom='1440' w:left='1440'/></w:sectPr>");
        var entries=new TreeMap<String,String>();
        entries.put("[Content_Types].xml","<?xml version='1.0' encoding='UTF-8'?><Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='xml' ContentType='application/xml'/><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/><Override PartName='/word/styles.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml'/></Types>");
        entries.put("_rels/.rels","<?xml version='1.0' encoding='UTF-8'?><Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='word/document.xml'/></Relationships>");
        entries.put("word/document.xml","<?xml version='1.0' encoding='UTF-8'?><w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body>"+body+"</w:body></w:document>");
        entries.put("word/_rels/document.xml.rels","<?xml version='1.0' encoding='UTF-8'?><Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='styles' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles' Target='styles.xml'/></Relationships>");
        entries.put("word/styles.xml","<?xml version='1.0' encoding='UTF-8'?><w:styles xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii='SimSun' w:hAnsi='SimSun' w:eastAsia='SimSun'/><w:sz w:val='24'/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after='120' w:line='360' w:lineRule='auto'/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type='paragraph' w:styleId='Normal'><w:name w:val='Normal'/></w:style><w:style w:type='paragraph' w:styleId='Title'><w:name w:val='Title'/><w:pPr><w:keepNext/></w:pPr><w:rPr><w:b/><w:sz w:val='36'/></w:rPr></w:style><w:style w:type='paragraph' w:styleId='Heading1'><w:name w:val='Heading 1'/><w:pPr><w:keepNext/></w:pPr><w:rPr><w:b/><w:sz w:val='28'/></w:rPr></w:style></w:styles>");
        try(var output=new ByteArrayOutputStream();var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {
            for(var entry:entries.entrySet()){var item=new ZipEntry(entry.getKey());item.setTime(0);zip.putNextEntry(item);zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
            zip.finish();return output.toByteArray();
        }catch(Exception failure){throw new IllegalStateException("Template asset creation failed",failure);}
    }
    private static String paragraph(String value,String style){return "<w:p><w:pPr><w:pStyle w:val='"+style+"'/></w:pPr><w:r><w:t xml:space='preserve'>"+escape(value)+"</w:t></w:r></w:p>";}
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
}
