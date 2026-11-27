package io.github.amatsukiichika.iwaram.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/**
 * 反序列化时丢掉 List 字段里的 null 元素。
 *
 * 默认 Gson 会把 JSON 数组里的 `null` 原样写进 Kotlin 的 `List<T>`（泛型擦除后没有运行时校验），
 * `{"results":[null]}` 这类坏响应于是变成含 null 的列表，消费端一解引用就 NPE。
 * 这里只包 **List** 类型（`Iterable`/`Set`/`Map` 一律走 Gson 原工厂，元素去重与键值语义不变），
 * 挂在 IwaraClient 的共享 Gson 上生效（见 [IwaraClient]）。
 */
object NullSkippingListTypeAdapterFactory : TypeAdapterFactory {

    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        // 只处理 List 字段；Set/Map 等集合保持 Gson 默认行为。
        if (type.rawType != List::class.java) return null
        return NullSkippingListAdapter(gson, gson.getDelegateAdapter(this, type))
    }
}

/** 用 JsonElement 读整棵数组、滤掉 null 元素后再交给 Gson 原 List 适配器。 */
private class NullSkippingListAdapter<T>(
    private val gson: Gson,
    private val delegate: TypeAdapter<T>
) : TypeAdapter<T>() {

    override fun write(out: JsonWriter, value: T) {
        delegate.write(out, value)
    }

    override fun read(reader: JsonReader): T {
        val element = gson.getAdapter(JsonElement::class.java).read(reader)
        // 非数组（含 null）原样交给代理，让 Gson 保持原有的报错/空值行为。
        if (element !is JsonArray) return delegate.fromJsonTree(element)
        val filtered = JsonArray()
        for (item in element) {
            if (!item.isJsonNull) filtered.add(item)
        }
        return delegate.fromJsonTree(filtered)
    }
}
