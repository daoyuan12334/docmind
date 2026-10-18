package com.docmind.kb;

import com.docmind.common.BizException;
import com.docmind.common.ErrorCode;
import com.docmind.security.AuthUser;
import com.docmind.kb.KbModels.KnowledgeBase;
import com.docmind.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 知识库访问校验。规则：管理员可访问全部；成员只能访问自己的库以及 TEAM 可见库。
 * RAG 检索、文档上传、对话都先过这一层，避免越权读取私有知识。
 */
@Component
@RequiredArgsConstructor
public class KbAccess {

    private final KnowledgeBaseRepository repository;

    public KnowledgeBase requireKb(long kbId) {
        AuthUser user = CurrentUser.getOrNull();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return requireKb(kbId, user.userId());
    }

    public KnowledgeBase requireKb(long kbId, long userId) {
        KnowledgeBase kb = repository.findById(kbId).orElseThrow(() -> BizException.notFound("知识库"));
        AuthUser user = CurrentUser.getOrNull();
        boolean admin = user != null && user.isAdmin();
        if (!kb.visibleTo(userId, admin)) {
            throw BizException.forbidden("无权访问该知识库");
        }
        return kb;
    }

    public KnowledgeBase requireOwner(long kbId, long userId) {
        KnowledgeBase kb = repository.findById(kbId).orElseThrow(() -> BizException.notFound("知识库"));
        AuthUser user = CurrentUser.getOrNull();
        boolean admin = user != null && user.isAdmin();
        if (!admin && !kb.ownerId().equals(userId)) {
            throw BizException.forbidden("仅知识库所有者或管理员可执行此操作");
        }
        return kb;
    }
}
