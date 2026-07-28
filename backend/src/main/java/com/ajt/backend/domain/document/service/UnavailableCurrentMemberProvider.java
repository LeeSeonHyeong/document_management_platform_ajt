package com.ajt.backend.domain.document.service;

import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class UnavailableCurrentMemberProvider implements CurrentMemberProvider {

    @Override
    public CurrentMember currentMember() {
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
}
