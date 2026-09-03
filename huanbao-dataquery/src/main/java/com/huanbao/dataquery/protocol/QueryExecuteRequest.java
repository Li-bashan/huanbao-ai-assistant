package com.huanbao.dataquery.protocol;

/** 内部问数执行请求；身份和组织范围只从请求头读取。 */
public record QueryExecuteRequest(String query, String conversationId) {

    public QueryExecuteRequest {
        query = query == null ? "" : query.trim();
        conversationId = conversationId == null ? "" : conversationId.trim();
    }
}
