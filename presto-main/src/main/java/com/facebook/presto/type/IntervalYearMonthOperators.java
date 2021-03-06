/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.type;

import com.facebook.presto.operator.scalar.ScalarOperator;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.BooleanType;
import com.facebook.presto.spi.type.DoubleType;
import com.facebook.presto.spi.type.SqlIntervalYearMonth;
import com.facebook.presto.spi.type.IntervalYearMonthType;
import com.facebook.presto.spi.type.VarcharType;
import io.airlift.slice.Slice;

import static com.facebook.presto.metadata.OperatorType.ADD;
import static com.facebook.presto.metadata.OperatorType.BETWEEN;
import static com.facebook.presto.metadata.OperatorType.CAST;
import static com.facebook.presto.metadata.OperatorType.DIVIDE;
import static com.facebook.presto.metadata.OperatorType.EQUAL;
import static com.facebook.presto.metadata.OperatorType.GREATER_THAN;
import static com.facebook.presto.metadata.OperatorType.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.metadata.OperatorType.HASH_CODE;
import static com.facebook.presto.metadata.OperatorType.LESS_THAN;
import static com.facebook.presto.metadata.OperatorType.LESS_THAN_OR_EQUAL;
import static com.facebook.presto.metadata.OperatorType.MULTIPLY;
import static com.facebook.presto.metadata.OperatorType.NEGATION;
import static com.facebook.presto.metadata.OperatorType.NOT_EQUAL;
import static com.facebook.presto.metadata.OperatorType.SUBTRACT;
import static io.airlift.slice.Slices.utf8Slice;

public final class IntervalYearMonthOperators
{
    private IntervalYearMonthOperators()
    {
    }

    @ScalarOperator(ADD)
    @SqlType(IntervalYearMonthType.NAME)
    public static long add(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return left + right;
    }

    @ScalarOperator(SUBTRACT)
    @SqlType(IntervalYearMonthType.NAME)
    public static long subtract(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return left - right;
    }

    @ScalarOperator(MULTIPLY)
    @SqlType(IntervalYearMonthType.NAME)
    public static long multiplyByBigint(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(BigintType.NAME) long right)
    {
        return left * right;
    }

    @ScalarOperator(MULTIPLY)
    @SqlType(IntervalYearMonthType.NAME)
    public static long multiplyByDouble(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(DoubleType.NAME) double right)
    {
        return (long) (left * right);
    }

    @ScalarOperator(MULTIPLY)
    @SqlType(IntervalYearMonthType.NAME)
    public static long bigintMultiply(@SqlType(BigintType.NAME) long left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return left * right;
    }

    @ScalarOperator(MULTIPLY)
    @SqlType(IntervalYearMonthType.NAME)
    public static long doubleMultiply(@SqlType(DoubleType.NAME) double left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return (long) (left * right);
    }

    @ScalarOperator(DIVIDE)
    @SqlType(IntervalYearMonthType.NAME)
    public static long divideByDouble(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(DoubleType.NAME) double right)
    {
        return (long) (left / right);
    }

    @ScalarOperator(NEGATION)
    @SqlType(IntervalYearMonthType.NAME)
    public static long negate(@SqlType(IntervalYearMonthType.NAME) long value)
    {
        return -value;
    }

    @ScalarOperator(EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean equal(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return left == right;
    }

    @ScalarOperator(NOT_EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean notEqual(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return left != right;
    }

    @ScalarOperator(LESS_THAN)
    @SqlType(BooleanType.NAME)
    public static boolean lessThan(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return left < right;
    }

    @ScalarOperator(LESS_THAN_OR_EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean lessThanOrEqual(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return left <= right;
    }

    @ScalarOperator(GREATER_THAN)
    @SqlType(BooleanType.NAME)
    public static boolean greaterThan(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return left > right;
    }

    @ScalarOperator(GREATER_THAN_OR_EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean greaterThanOrEqual(@SqlType(IntervalYearMonthType.NAME) long left, @SqlType(IntervalYearMonthType.NAME) long right)
    {
        return left >= right;
    }

    @ScalarOperator(BETWEEN)
    @SqlType(BooleanType.NAME)
    public static boolean between(
            @SqlType(IntervalYearMonthType.NAME) long value,
            @SqlType(IntervalYearMonthType.NAME) long min,
            @SqlType(IntervalYearMonthType.NAME) long max)
    {
        return min <= value && value <= max;
    }

    @ScalarOperator(CAST)
    @SqlType(VarcharType.NAME)
    public static Slice castToSlice(@SqlType(IntervalYearMonthType.NAME) long value)
    {
        return utf8Slice(SqlIntervalYearMonth.formatMonths(value));
    }

    @ScalarOperator(HASH_CODE)
    @SqlType(BigintType.NAME)
    public static long hashCode(@SqlType(IntervalYearMonthType.NAME) long value)
    {
        return (int) (value ^ (value >>> 32));
    }
}
