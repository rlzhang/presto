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
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.BooleanType;
import com.facebook.presto.spi.type.DateType;
import com.facebook.presto.spi.type.TimestampType;
import com.facebook.presto.spi.type.TimestampWithTimeZoneType;
import com.facebook.presto.spi.type.VarcharType;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.joda.time.chrono.ISOChronology;

import static com.facebook.presto.metadata.OperatorType.BETWEEN;
import static com.facebook.presto.metadata.OperatorType.CAST;
import static com.facebook.presto.metadata.OperatorType.EQUAL;
import static com.facebook.presto.metadata.OperatorType.GREATER_THAN;
import static com.facebook.presto.metadata.OperatorType.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.metadata.OperatorType.HASH_CODE;
import static com.facebook.presto.metadata.OperatorType.LESS_THAN;
import static com.facebook.presto.metadata.OperatorType.LESS_THAN_OR_EQUAL;
import static com.facebook.presto.metadata.OperatorType.NOT_EQUAL;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_CAST_ARGUMENT;
import static com.facebook.presto.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static com.facebook.presto.util.DateTimeUtils.parseDate;
import static com.facebook.presto.util.DateTimeUtils.printDate;
import static com.facebook.presto.util.DateTimeZoneIndex.getChronology;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class DateOperators
{
    private DateOperators()
    {
    }

    @ScalarOperator(EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean equal(@SqlType(DateType.NAME) long left, @SqlType(DateType.NAME) long right)
    {
        return left == right;
    }

    @ScalarOperator(NOT_EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean notEqual(@SqlType(DateType.NAME) long left, @SqlType(DateType.NAME) long right)
    {
        return left != right;
    }

    @ScalarOperator(LESS_THAN)
    @SqlType(BooleanType.NAME)
    public static boolean lessThan(@SqlType(DateType.NAME) long left, @SqlType(DateType.NAME) long right)
    {
        return left < right;
    }

    @ScalarOperator(LESS_THAN_OR_EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean lessThanOrEqual(@SqlType(DateType.NAME) long left, @SqlType(DateType.NAME) long right)
    {
        return left <= right;
    }

    @ScalarOperator(GREATER_THAN)
    @SqlType(BooleanType.NAME)
    public static boolean greaterThan(@SqlType(DateType.NAME) long left, @SqlType(DateType.NAME) long right)
    {
        return left > right;
    }

    @ScalarOperator(GREATER_THAN_OR_EQUAL)
    @SqlType(BooleanType.NAME)
    public static boolean greaterThanOrEqual(@SqlType(DateType.NAME) long left, @SqlType(DateType.NAME) long right)
    {
        return left >= right;
    }

    @ScalarOperator(BETWEEN)
    @SqlType(BooleanType.NAME)
    public static boolean between(@SqlType(DateType.NAME) long value, @SqlType(DateType.NAME) long min, @SqlType(DateType.NAME) long max)
    {
        return min <= value && value <= max;
    }

    @ScalarOperator(CAST)
    @SqlType(TimestampType.NAME)
    public static long castToTimestamp(ConnectorSession session, @SqlType(DateType.NAME) long value)
    {
        // date is encoded as milliseconds at midnight in UTC
        // convert to midnight if the session timezone
        ISOChronology chronology = getChronology(session.getTimeZoneKey());
        return value - chronology.getZone().getOffset(value);
    }

    @ScalarOperator(CAST)
    @SqlType(TimestampWithTimeZoneType.NAME)
    public static long castToTimestampWithTimeZone(ConnectorSession session, @SqlType(DateType.NAME) long value)
    {
        // date is encoded as milliseconds at midnight in UTC
        // convert to midnight if the session timezone
        ISOChronology chronology = getChronology(session.getTimeZoneKey());
        long millis = value - chronology.getZone().getOffset(value);
        return packDateTimeWithZone(millis, session.getTimeZoneKey());
    }

    @ScalarOperator(CAST)
    @SqlType(VarcharType.NAME)
    public static Slice castToSlice(@SqlType(DateType.NAME) long value)
    {
        return Slices.copiedBuffer(printDate(value), UTF_8);
    }

    @ScalarOperator(CAST)
    @SqlType(DateType.NAME)
    public static long castFromSlice(@SqlType(VarcharType.NAME) Slice value)
    {
        try {
            return parseDate(value.toStringUtf8());
        }
        catch (IllegalArgumentException e) {
            throw new PrestoException(INVALID_CAST_ARGUMENT.toErrorCode(), e);
        }
    }

    @ScalarOperator(HASH_CODE)
    @SqlType(BigintType.NAME)
    public static long hashCode(@SqlType(DateType.NAME) long value)
    {
        return (int) (value ^ (value >>> 32));
    }
}
