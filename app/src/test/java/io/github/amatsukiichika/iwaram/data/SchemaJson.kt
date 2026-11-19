package io.github.amatsukiichika.iwaram.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import java.io.File

/** Room 导出 createSql 里的表名占位符；Room 在运行时才把它填成真实表名。 */
private const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"

/**
 * 测试侧读取 Room 导出的 schema（app/schemas/…AppDatabase/<version>.json）并比对真实库形状。
 *
 * 迁移测试的规矩是「打开上一版的导出 schema」，而导出 schema 必须自己有消费者：
 * - 旧库不再手抄 DDL，直接用导出 JSON 里 Room 生成的 createSql 建出来（[ExportedSchema.createStatements]）；
 * - 迁移后用 PRAGMA table_info / index_list / foreign_key_list 读出实际形状，与终点版本的
 *   导出 schema 逐表比对（[assertMatchesExported]）。
 *
 * 这样实体改了而导出没跟上、或迁移 SQL 与实体期望不一致，都会以可读的断言失败报出来。
 */
internal object SchemaJson {

    /** AppDatabase 全限定名，决定导出目录名。 */
    private const val DATABASE_CLASS = "io.github.amatsukiichika.iwaram.data.AppDatabase"

    /** 单测工作目录通常是模块目录（app/）；第二个候选覆盖从仓库根目录直接跑的情况。 */
    private val SCHEMA_DIR_CANDIDATES = listOf(
        "schemas/$DATABASE_CLASS",
        "app/schemas/$DATABASE_CLASS"
    )

    /** 读取并解析 [version] 的导出 schema；文件缺失或格式不对时抛带路径的断言错误。 */
    fun load(version: Int): ExportedSchema {
        val file = schemaFile(version)
        val root = try {
            JsonParser.parseString(file.readText()).asJsonObject
        } catch (error: RuntimeException) {
            throw AssertionError("无法解析导出的 schema ${file.absolutePath}: ${error.message}", error)
        }
        val database = root.get("database")?.asJsonObject
            ?: throw AssertionError("导出的 schema 缺少 database 节点：${file.absolutePath}")
        val exportedVersion = database.get("version").asInt
        assertEquals("导出 schema 的版本号与请求不符：${file.absolutePath}", version, exportedVersion)
        val tables = database.getAsJsonArray("entities")?.map { it.asJsonObject.toTableShape() }
            ?: throw AssertionError("导出的 schema 缺少 entities 数组：${file.absolutePath}")
        return ExportedSchema(version, tables)
    }

    /** 断言 [db] 的实际形状与它 user_version 指向的导出 schema 一致。 */
    fun assertMatchesExported(db: SupportSQLiteDatabase) {
        val version = db.version
        val schema = load(version)
        val file = schemaFile(version)
        assertEquals(
            "迁移后库里的表集合与导出的 v$version schema 不一致（schema: ${file.absolutePath}）",
            schema.tables.map { it.name }.sorted(),
            tableNames(db).sorted()
        )
        schema.tables.forEach { expected -> assertTableMatches(version, file, db, expected) }
    }

    private fun assertTableMatches(
        version: Int,
        file: File,
        db: SupportSQLiteDatabase,
        expected: TableShape
    ) {
        val actual = readTableShape(db, expected.name)
        val where = "表 `${expected.name}` 与导出的 v$version schema 不一致（schema: ${file.absolutePath}）"
        assertEquals(
            "$where：列（顺序/类型/NOT NULL/默认值）",
            expected.columns.map { it.describe() },
            actual.columns.map { it.describe() }
        )
        assertEquals("$where：主键列", expected.primaryKey, actual.primaryKey)
        assertEquals(
            "$where：索引（名字/唯一性/列序）",
            expected.indices.map { it.describe() }.sorted(),
            actual.indices.map { it.describe() }.sorted()
        )
        assertEquals(
            "$where：外键",
            expected.foreignKeys.map { it.describe() }.sorted(),
            actual.foreignKeys.map { it.describe() }.sorted()
        )
    }

    /**
     * 从真实库读出 [table] 的形状。
     *
     * index_list 只取 origin='c' 的显式索引：主键/唯一约束的自动索引（origin='pk'/'u'）由主键断言覆盖。
     */
    private fun readTableShape(db: SupportSQLiteDatabase, table: String): TableShape {
        val columns = mutableListOf<ColumnShape>()
        val primaryKey = mutableListOf<Pair<Int, String>>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                columns.add(
                    ColumnShape(
                        name = cursor.getString(nameIndex),
                        affinity = cursor.getString(typeIndex).uppercase(),
                        notNull = cursor.getInt(notNullIndex) != 0,
                        defaultValue = if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
                    )
                )
                val primaryKeyOrder = cursor.getInt(primaryKeyIndex)
                if (primaryKeyOrder > 0) primaryKey.add(primaryKeyOrder to columns.last().name)
            }
        }
        val indices = mutableListOf<IndexShape>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            val originIndex = cursor.getColumnIndexOrThrow("origin")
            while (cursor.moveToNext()) {
                if (cursor.getString(originIndex) != "c") continue
                val indexName = cursor.getString(nameIndex)
                indices.add(
                    IndexShape(
                        name = indexName,
                        unique = cursor.getInt(uniqueIndex) != 0,
                        columns = indexColumns(db, indexName)
                    )
                )
            }
        }
        val foreignKeys = mutableListOf<ForeignKeyShape>()
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            val tableIndex = cursor.getColumnIndexOrThrow("table")
            val fromIndex = cursor.getColumnIndexOrThrow("from")
            val toIndex = cursor.getColumnIndexOrThrow("to")
            val onDeleteIndex = cursor.getColumnIndexOrThrow("on_delete")
            val onUpdateIndex = cursor.getColumnIndexOrThrow("on_update")
            while (cursor.moveToNext()) {
                foreignKeys.add(
                    ForeignKeyShape(
                        table = cursor.getString(tableIndex),
                        from = cursor.getString(fromIndex),
                        to = cursor.getString(toIndex),
                        onDelete = cursor.getString(onDeleteIndex),
                        onUpdate = cursor.getString(onUpdateIndex)
                    )
                )
            }
        }
        return TableShape(
            name = table,
            createSql = "",
            columns = columns,
            primaryKey = primaryKey.sortedBy { it.first }.map { it.second },
            indices = indices,
            foreignKeys = foreignKeys
        )
    }

    private fun indexColumns(db: SupportSQLiteDatabase, indexName: String): List<String> {
        val columns = sortedMapOf<Int, String>()
        db.query("PRAGMA index_info(`$indexName`)").use { cursor ->
            val sequenceIndex = cursor.getColumnIndexOrThrow("seqno")
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns[cursor.getInt(sequenceIndex)] = cursor.getString(nameIndex)
            }
        }
        return columns.values.toList()
    }

    /** 库里的业务表名（排除 Room 的 room_master_table 与 framework 的 android_metadata）。 */
    private fun tableNames(db: SupportSQLiteDatabase): List<String> {
        val names = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata')"
        ).use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }
        return names
    }

    private fun schemaFile(version: Int): File {
        val candidates = SCHEMA_DIR_CANDIDATES.map { File(it, "$version.json") }
        return candidates.firstOrNull { it.isFile } ?: throw AssertionError(
            "找不到导出的 Room schema v$version（cwd=${File("").absolutePath}）；已尝试：" +
                candidates.joinToString { it.absolutePath }
        )
    }

    private fun JsonObject.toTableShape(): TableShape {
        val tableName = get("tableName").asString
        val columns = getAsJsonArray("fields")?.map { element ->
            val field = element.asJsonObject
            ColumnShape(
                name = field.get("columnName").asString,
                affinity = field.get("affinity").asString.uppercase(),
                notNull = field.get("notNull")?.asBoolean ?: false,
                defaultValue = field.get("defaultValue")?.asString
            )
        } ?: emptyList()
        val primaryKey = get("primaryKey")?.asJsonObject
            ?.getAsJsonArray("columnNames")
            ?.map { it.asString }
            ?: emptyList()
        val indices = getAsJsonArray("indices")?.map { element ->
            val index = element.asJsonObject
            IndexShape(
                name = index.get("name").asString,
                unique = index.get("unique")?.asBoolean ?: false,
                columns = index.getAsJsonArray("columnNames").map { it.asString },
                createSql = index.get("createSql")?.asString.orEmpty()
            )
        } ?: emptyList()
        val foreignKeys = getAsJsonArray("foreignKeys")?.flatMap { element ->
            val foreignKey = element.asJsonObject
            val onDelete = foreignKey.get("onDelete")?.asString ?: "NO ACTION"
            val onUpdate = foreignKey.get("onUpdate")?.asString ?: "NO ACTION"
            foreignKey.getAsJsonArray("columns").map { child ->
                val column = child.asJsonObject
                ForeignKeyShape(
                    table = foreignKey.get("table").asString,
                    from = column.get("from").asString,
                    to = column.get("to").asString,
                    onDelete = onDelete,
                    onUpdate = onUpdate
                )
            }
        } ?: emptyList()
        return TableShape(
            name = tableName,
            createSql = get("createSql").asString,
            columns = columns,
            primaryKey = primaryKey,
            indices = indices,
            foreignKeys = foreignKeys
        )
    }

    private fun ColumnShape.describe(): String {
        val notNull = if (notNull) " NOT NULL" else ""
        val default = if (defaultValue == null) "" else " DEFAULT $defaultValue"
        return "$name $affinity$notNull$default"
    }

    private fun IndexShape.describe(): String =
        (if (unique) "UNIQUE " else "") + name + "(" + columns.joinToString(", ") + ")"

    private fun ForeignKeyShape.describe(): String =
        "$from -> $table($to) ON DELETE $onDelete ON UPDATE $onUpdate"

    /** 一个版本导出的库结构。 */
    data class ExportedSchema(val version: Int, val tables: List<TableShape>) {

        fun table(name: String): TableShape =
            tables.firstOrNull { it.name == name }
                ?: throw AssertionError("导出的 v$version schema 里没有表 $name")

        /** 按 Room 建库顺序（先所有表，再所有索引）填好表名，返回可直接执行的建库语句。 */
        fun createStatements(): List<String> {
            val tableStatements = tables.map { table ->
                table.createSql.replace(TABLE_NAME_PLACEHOLDER, table.name)
            }
            val indexStatements = tables.flatMap { table ->
                table.indices.map { index ->
                    index.createSql.replace(TABLE_NAME_PLACEHOLDER, table.name)
                }
            }
            return tableStatements + indexStatements
        }
    }

    /** 一张表的导出形状；[createSql] 是 Room 的建表模板（含表名占位符）。 */
    data class TableShape(
        val name: String,
        val createSql: String,
        val columns: List<ColumnShape>,
        val primaryKey: List<String>,
        val indices: List<IndexShape>,
        val foreignKeys: List<ForeignKeyShape>
    )

    data class ColumnShape(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?
    )

    data class IndexShape(
        val name: String,
        val unique: Boolean,
        val columns: List<String>,
        val createSql: String = ""
    )

    data class ForeignKeyShape(
        val table: String,
        val from: String,
        val to: String,
        val onDelete: String,
        val onUpdate: String
    )
}
