package com.example.aibalance;

/**
 * 一个 AI 服务提供商的配置。
 * id       用于区分服务商 & 作为 SharedPreferences 存储 Key 的键
 * name     界面上显示的名称
 * endpoint 查询余额的 HTTP 接口地址
 */
public class Provider {
    public final String id;
    public final String name;
    public final String endpoint;

    public Provider(String id, String name, String endpoint) {
        this.id = id;
        this.name = name;
        this.endpoint = endpoint;
    }
}
