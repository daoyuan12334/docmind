package com.docmind.ingest;

import com.docmind.kb.KbModels;

/** 文档解析器：按模态/扩展名选择实现，返回统一的 ParsedDocument。 */
public interface DocumentParser {

    boolean supports(KbModels.Modality modality, String ext);

    ParsedDocument parse(KbModels.Document doc, byte[] bytes) throws Exception;
}
