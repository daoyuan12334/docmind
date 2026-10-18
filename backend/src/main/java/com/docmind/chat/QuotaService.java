package com.docmind.chat;

import com.docmind.common.BizException;
import com.docmind.common.ErrorCode;
import com.docmind.config.AppProperties;
import com.docmind.infra.cache.CachePort;
import com.docmind.observe.AiUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 限流与配额：分钟级请求数 + 每日 token 上限，防止误用把账单打爆。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final CachePort cache;
    private final AppProperties props;
    private final AiUsageRepository usage;

    public void checkRequest(long userId) {
        if (!props.getQuota().isEnabled()) {
            return;
        }
        long count = cache.increment(rateKey(userId), Duration.ofMinutes(1));
        if (count > props.getQuota().getRequestsPerMinute()) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED,
                    "请求过于频繁，每分钟上限 " + props.getQuota().getRequestsPerMinute() + " 次");
        }
    }

    public void checkTokenBudget(long userId) {
        if (!props.getQuota().isEnabled()) {
            return;
        }
        long used = usage.sumTokensToday(userId);
        if (used >= props.getQuota().getDailyTokensPerUser()) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED,
                    "今日 token 配额已用尽（" + used + "/" + props.getQuota().getDailyTokensPerUser() + "）");
        }
    }

    private String rateKey(long userId) {
        return "docmind:rate:" + userId + ":" + (System.currentTimeMillis() / 60000);
    }
}
