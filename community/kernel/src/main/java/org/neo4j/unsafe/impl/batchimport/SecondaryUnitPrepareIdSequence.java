/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.impl.batchimport;

import java.util.function.Function;
import java.util.function.LongFunction;

import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.id.IdRange;
import org.neo4j.kernel.impl.store.id.IdSequence;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;

public class SecondaryUnitPrepareIdSequence<RECORD extends AbstractBaseRecord>
        implements Function<RecordStore<RECORD>,LongFunction<IdSequence>>
{
    @Override
    public LongFunction<IdSequence> apply( RecordStore<RECORD> store )
    {
        return new Something();
    }

    private static class Something implements LongFunction<IdSequence>, IdSequence
    {
        private long id;
        private boolean returned;

        @Override
        public IdSequence apply( long firstUnitId )
        {
            this.id = firstUnitId;
            returned = false;
            return this;
        }

        @Override
        public long nextId()
        {
            try
            {
                assert !returned;
                return id + 1;
            }
            finally
            {
                returned = true;
            }
        }

        @Override
        public IdRange nextIdBatch( int size )
        {
            throw new UnsupportedOperationException();
        }
    }
}
