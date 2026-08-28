package com.huanbao.aigateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huanbao.aigateway.dto.DataQueryAccessResponse;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.repository.DataQueryUserRepository;
import org.junit.jupiter.api.Test;

class DataQueryAccessServiceTest {
    private final DataQueryUserRepository repository = org.mockito.Mockito.mock(DataQueryUserRepository.class);
    private final DataQueryAccessService service = new DataQueryAccessService(repository);

    @Test
    void trimsNameAndReturnsCoveredForEnabledUser() {
        when(repository.existsByUserNameAndEnabled("张三", true)).thenReturn(true);

        DataQueryAccessResponse result = service.check("  张三 ");

        assertEquals("张三", result.userName());
        org.junit.jupiter.api.Assertions.assertTrue(result.covered());
        verify(repository).existsByUserNameAndEnabled("张三", true);
    }

    @Test
    void disabledOrMissingUserIsNotCovered() {
        when(repository.existsByUserNameAndEnabled("李四", true)).thenReturn(false);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.requireCovered("李四")
        );

        assertEquals("DATA_QUERY_NOT_COVERED", exception.getCode());
    }

    @Test
    void missingNameFailsClosedWithBusinessCode() {
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.requireCovered("  ")
        );

        assertEquals("CURRENT_USER_MISSING", exception.getCode());
    }
}
