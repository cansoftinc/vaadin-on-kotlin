package com.github.vok.framework.sql2o.vaadin

import com.github.vok.framework.sql2o.Dao
import com.github.vok.framework.sql2o.Entity
import com.github.vok.framework.sql2o.databaseTableName
import com.github.vok.framework.sql2o.db
import com.vaadin.data.provider.AbstractBackEndDataProvider
import com.vaadin.data.provider.ConfigurableFilterDataProvider
import com.vaadin.data.provider.DataProvider
import com.vaadin.data.provider.Query
import com.vaadin.shared.data.sort.SortDirection
import java.io.Serializable
import java.util.stream.Stream
import kotlin.reflect.KProperty1
import kotlin.streams.toList

/**
 * Provides instances of entities of given [clazz] from a database. Does not support joins on any of the like; supports filtering
 * and sorting. Only supports simple views over one database table (one entity) - for anything more complex please use [SqlDataProvider].
 */
class EntityDataProvider<T : Entity<*>>(val clazz: Class<T>) : AbstractBackEndDataProvider<T, Filter<T>?>() {
    override fun getId(item: T): Any = item.id!!
    override fun toString() = "EntityDataProvider($clazz)"

    override fun sizeInBackEnd(query: Query<T, Filter<T>?>?): Int = db {
        val count = con.createQuery("select count(*) ${query.toSQLFromClause(false)}")
                .fillInParamsFromFilters(query)
                .executeScalar()
        (count as Number).toInt()
    }

    override fun fetchFromBackEnd(query: Query<T, Filter<T>?>?): Stream<T> = db {
        val list = con.createQuery("select * ${query.toSQLFromClause(true)}")
                .fillInParamsFromFilters(query)
                .executeAndFetch(clazz)
        list.stream()
    }

    private fun org.sql2o.Query.fillInParamsFromFilters(query: Query<T, Filter<T>?>?): org.sql2o.Query {
        val filters = query?.filter?.orElse(null) ?: return this
        val params = filters.getSQL92Parameters()
        params.entries.forEach { (name, value) -> addParameter(name, value) }
        return this
    }

    private fun Query<T, Filter<T>?>?.toSQLFromClause(includeOrderBy: Boolean): String {
        val where: String? = this?.filter?.orElse(null)?.toSQL92()
        val orderBy = this?.sortOrders?.map { "${it.sorted} ${if (it.direction == SortDirection.ASCENDING) "ASC" else "DESC"}" }?.joinToString()
        val offset = this?.offset
        val limit = this?.limit.takeUnless { it == Int.MAX_VALUE }
        val sql = buildString {
            append("from ${clazz.databaseTableName}")
            if (where != null && where.isNotBlank()) append(" where $where")
            if (orderBy != null && orderBy.isNotBlank() && includeOrderBy) append(" order by $orderBy")
            if (offset != null && limit != null) append(" offset $offset limit $limit")
        }
        return sql
    }
}

/**
 * Wraps this data provider in a configurable filter, regardless of whether this data provider is already a configurable filter or not.
 * @return data provider which can be configured to always apply given filter.
 */
fun <T: Any> DataProvider<T, in Filter<T>?>.configurableFilter() : ConfigurableFilterDataProvider<T, Filter<T>?, Filter<T>?> =
        withConfigurableFilter({ f1: Filter<T>?, f2: Filter<T>? -> listOf(f1, f2).filterNotNull().toSet().and() })

/**
 * Produces a new data provider which restricts rows returned by the original data provider to given filter.
 *
 * Invoking this method multiple times will restrict the rows further.
 * @param other applies this filter
 */
fun <T: Any> DataProvider<T, in Filter<T>?>.and(other: Filter<T>) : DataProvider<T, Filter<T>?> = configurableFilter().apply {
    setFilter(other)
}

/**
 * Produces a new data provider which restricts rows returned by the original data provider to given filter. Allows you to write
 * expressions like this: `sqlDataProvider<Person>().and { Person::age lt 25 }`
 * See [SqlWhereBuilder] for a complete list of applicable operators.
 *
 * Invoking this method multiple times will restrict the rows further.
 * @param block the block which allows you to build the `where` expression.
 */
fun <T: Any> DataProvider<T, in Filter<T>?>.and(block: SqlWhereBuilder<T>.()-> Filter<T>) : DataProvider<T, Filter<T>?> = configurableFilter().apply {
    setFilter(block)
}

/**
 * Creates a filter programatically: `filter { Person::age lt 25 }`
 */
fun <T: Any> filter(block: SqlWhereBuilder<T>.()-> Filter<T>): Filter<T> = block(SqlWhereBuilder())

/**
 * Removes the original filter and sets the new filter. Allows you to write
 * expressions like this: `sqlDataProvider<Person>().and { Person::age lt 25 }`.
 * See [SqlWhereBuilder] for a complete list of applicable operators.
 *
 * Invoking this method multiple times will overwrite the previous filter.
 * @param block the block which allows you to build the `where` expression.
 */
fun <T: Any> ConfigurableFilterDataProvider<T, Filter<T>?, Filter<T>?>.setFilter(block: SqlWhereBuilder<T>.()-> Filter<T>) {
    setFilter(block(SqlWhereBuilder()))
}

fun <T: Any> Set<Filter<T>>.and(): Filter<T>? = when (size) {
    0 -> null
    1 -> iterator().next()
    else -> AndFilter(this)
}
fun <T: Any> Set<Filter<T>>.or(): Filter<T>? = when (size) {
    0 -> null
    1 -> iterator().next()
    else -> OrFilter(this)
}

/**
 * Running block with this class as its receiver will allow you to write expressions like this:
 * `Person::age lt 25`. Does not support joins - just use the plain old SQL 92 where syntax for that ;)
 *
 * Containing these functions in this class will prevent polluting of the KProperty1 interface and also makes it type-safe.
 *
 * This looks like too much Kotlin syntax magic. Promise me to use this for simple Entities and/or programmatic where creation only ;)
 */
class SqlWhereBuilder<T: Any> {
    infix fun <R: Serializable?> KProperty1<T, R>.eq(value: R): Filter<T> = EqFilter(name, value)
    @Suppress("UNCHECKED_CAST")
    infix fun <R> KProperty1<T, R?>.le(value: R): Filter<T> = OpFilter(name, value as Comparable<Any>, CompareOperator.le)
    @Suppress("UNCHECKED_CAST")
    infix fun <R> KProperty1<T, R?>.lt(value: R): Filter<T> = OpFilter(name, value as Comparable<Any>, CompareOperator.lt)
    @Suppress("UNCHECKED_CAST")
    infix fun <R> KProperty1<T, R?>.ge(value: R): Filter<T> = OpFilter(name, value as Comparable<Any>, CompareOperator.ge)
    @Suppress("UNCHECKED_CAST")
    infix fun <R> KProperty1<T, R?>.gt(value: R): Filter<T> = OpFilter(name, value as Comparable<Any>, CompareOperator.gt)
    infix fun KProperty1<T, String?>.like(value: String): Filter<T> = LikeFilter(name, value)
    /**
     * Matches only values contained in given range.
     * @param range the range
     */
    infix fun <R> KProperty1<T, R?>.between(range: ClosedRange<R>): Filter<T> where R: Number, R: Comparable<R> =
            this.ge(range.start as Number) and this.le(range.endInclusive as Number)
    val KProperty1<T, *>.isNull: Filter<T> get() = IsNullFilter(name)
    val KProperty1<T, *>.isNotNull: Filter<T> get() = IsNotNullFilter(name)
    val KProperty1<T, Boolean?>.isTrue: Filter<T> get() = EqFilter(name, true)
    val KProperty1<T, Boolean?>.isFalse: Filter<T> get() = EqFilter(name, false)
    /**
     * Allows for a native query: `"age < :age_p"("age_p" to 60)`
     */
    operator fun String.invoke(vararg params: Pair<String, Any?>) = NativeSqlFilter<T>(this, mapOf(*params))
}

/**
 * Allows you to simply create a data provider off your entity: `grid.dataProvider = Person.dataProvider`. This data provider
 * doesn't support any joins or more complex queries; to use those please use [SqlDataProvider].
 */
inline val <reified T: Entity<*>> Dao<T>.dataProvider: DataProvider<T, Filter<T>?> get() = EntityDataProvider(T::class.java)

/**
 * Just write any native SQL into [where], e.g. `age > 25 and name like :name`; don't forget to properly fill in the [params] map.
 *
 * Does not support in-memory filtering and will throw an exception.
 */
data class NativeSqlFilter<T: Any>(val where: String, val params: Map<String, Any?>) : Filter<T> {
    override fun test(t: T) = throw IllegalStateException("Native sql filter does not support in-memory filtering")
    override fun toSQL92() = where
    override fun getSQL92Parameters(): Map<String, Any?> = params
}

/**
 * Returns all items provided by this data provider as an eager list. Careful with larger data!
 */
fun <T: Any, F> DataProvider<T, F>.getAll(): List<T> = fetch(Query()).toList()
