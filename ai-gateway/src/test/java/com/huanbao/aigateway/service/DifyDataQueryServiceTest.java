package com.huanbao.aigateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import com.huanbao.aigateway.dto.DifyDataQueryRequest;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.repository.DifyDataQueryRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DifyDataQueryServiceTest {
    private final DifyDataQueryRepository repository = org.mockito.Mockito.mock(DifyDataQueryRepository.class);
    private final DataQueryAccessService accessService = org.mockito.Mockito.mock(DataQueryAccessService.class);
    private final DifyDataQueryService service = new DifyDataQueryService(repository, accessService);

    @Test
    void queryStopsBeforeRepositoryWhenAccessIsDenied() {
        org.mockito.Mockito.doThrow(new BusinessException(
            "DATA_QUERY_NOT_COVERED",
            "not covered"
        )).when(accessService).requireCovered("李四");

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.query(new DifyDataQueryRequest(
                "action_audit_recent",
                Map.of(),
                "李四"
            ))
        );

        assertEquals("DATA_QUERY_NOT_COVERED", exception.getCode());
        verifyNoInteractions(repository);
    }
}
