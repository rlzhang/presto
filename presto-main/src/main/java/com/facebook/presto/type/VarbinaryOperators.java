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
import com.facebook.presto.spi.type.VarbinaryType;
import io.airlift.slice.Slice;

import static com.facebook.presto.metadata.OperatorType.BETWEEN;
import static com.facebook.presto.metadata.OperatorType.EQUAL;
import static com.facebook.presto.metadata.OperatorType.GREATER_THAN;
import static com.facebook.presto.metadata.OperatorType.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.metadata.OperatorType.HASH_CODE;
import static com.facebook.presto.metadata.OperatorType.LESS_THAN;
import static com.facebook.presto.metadata.OperatorType.LESS_THAN_OR_EQUAL;
import static com.facebook.presto.metadata.OperatorType.NOT_EQUAL;

public final class VarbinaryOperators
{
    private VarbinaryOperators()
    {
    }

    @ScalarOperator(EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean equal(@SqlType(VarbinaryType.NAME) Slice left, @SqlType(VarbinaryType.NAME) Slice right)
    {
        return left.equals(right);
    }

    @ScalarOperator(NOT_EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean notEqual(@SqlType(VarbinaryType.NAME) Slice left, @SqlType(VarbinaryType.NAME) Slice right)
    {
        return !left.equals(right);
    }

    @ScalarOperator(LESS_THAN)
    @SqlType(BooleanType.NAME)
    public static boolean lessThan(@SqlType(VarbinaryType.NAME) Slice left, @SqlType(VarbinaryType.NAME) Slice right)
    {
        return left.compareTo(right) < 0;
    }

    @ScalarOperator(LESS_THAN_OR_EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean lessThanOrEqual(@SqlType(VarbinaryType.NAME) Slice left, @SqlType(VarbinaryType.NAME) Slice right)
    {
        return left.compareTo(right) <= 0;
    }

    @ScalarOperator(GREATER_THAN)
    @SqlType(BooleanType.NAME)
    public static boolean greaterThan(@SqlType(VarbinaryType.NAME) Slice left, @SqlType(VarbinaryType.NAME) Slice right)
    {
        return left.compareTo(right) > 0;
    }

    @ScalarOperator(GREATER_THAN_OR_EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean greaterThanOrEqual(@SqlType(VarbinaryType.NAME) Slice left, @SqlType(VarbinaryType.NAME) Slice right)
    {
        return left.compareTo(right) >= 0;
    }

    @ScalarOperator(BETWEEN)
    @SqlType(BooleanType.NAME)
    public static boolean between(@SqlType(VarbinaryType.NAME) Slice value, @SqlType(VarbinaryType.NAME) Slice min, @SqlType(VarbinaryType.NAME) Slice max)
    {
        return min.compareTo(value) <= 0 && value.compareTo(max) <= 0;
    }

    @ScalarOperator(HASH_CODE)
    @SqlType(BigintType.NAME)
    public static long hashCode(@SqlType(VarbinaryType.NAME) Slice value)
    {
        return value.hashCode();
    }
}
