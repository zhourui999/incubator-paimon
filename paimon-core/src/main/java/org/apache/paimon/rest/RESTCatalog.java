/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.rest;

import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogUtils;
import org.apache.paimon.catalog.Database;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.catalog.PropertyChange;
import org.apache.paimon.catalog.TableMetadata;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.operation.FileStoreCommit;
import org.apache.paimon.options.Options;
import org.apache.paimon.partition.Partition;
import org.apache.paimon.rest.auth.AuthSession;
import org.apache.paimon.rest.exceptions.AlreadyExistsException;
import org.apache.paimon.rest.exceptions.BadRequestException;
import org.apache.paimon.rest.exceptions.ForbiddenException;
import org.apache.paimon.rest.exceptions.NoSuchResourceException;
import org.apache.paimon.rest.exceptions.NotImplementedException;
import org.apache.paimon.rest.exceptions.ServiceFailureException;
import org.apache.paimon.rest.requests.AlterDatabaseRequest;
import org.apache.paimon.rest.requests.AlterPartitionsRequest;
import org.apache.paimon.rest.requests.AlterTableRequest;
import org.apache.paimon.rest.requests.CommitTableRequest;
import org.apache.paimon.rest.requests.CreateDatabaseRequest;
import org.apache.paimon.rest.requests.CreatePartitionsRequest;
import org.apache.paimon.rest.requests.CreateTableRequest;
import org.apache.paimon.rest.requests.CreateViewRequest;
import org.apache.paimon.rest.requests.DropPartitionsRequest;
import org.apache.paimon.rest.requests.MarkDonePartitionsRequest;
import org.apache.paimon.rest.requests.RenameTableRequest;
import org.apache.paimon.rest.responses.AlterDatabaseResponse;
import org.apache.paimon.rest.responses.CommitTableResponse;
import org.apache.paimon.rest.responses.ConfigResponse;
import org.apache.paimon.rest.responses.CreateDatabaseResponse;
import org.apache.paimon.rest.responses.ErrorResponseResourceType;
import org.apache.paimon.rest.responses.GetDatabaseResponse;
import org.apache.paimon.rest.responses.GetTableResponse;
import org.apache.paimon.rest.responses.GetTableTokenResponse;
import org.apache.paimon.rest.responses.GetViewResponse;
import org.apache.paimon.rest.responses.ListDatabasesResponse;
import org.apache.paimon.rest.responses.ListPartitionsResponse;
import org.apache.paimon.rest.responses.ListTablesResponse;
import org.apache.paimon.rest.responses.ListViewsResponse;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaChange;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.utils.Pair;
import org.apache.paimon.view.View;
import org.apache.paimon.view.ViewImpl;
import org.apache.paimon.view.ViewSchema;

import org.apache.paimon.shade.guava30.com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import static org.apache.paimon.CoreOptions.createCommitUser;
import static org.apache.paimon.catalog.CatalogUtils.checkNotBranch;
import static org.apache.paimon.catalog.CatalogUtils.checkNotSystemDatabase;
import static org.apache.paimon.catalog.CatalogUtils.checkNotSystemTable;
import static org.apache.paimon.catalog.CatalogUtils.isSystemDatabase;
import static org.apache.paimon.catalog.CatalogUtils.listPartitionsFromFileSystem;
import static org.apache.paimon.catalog.CatalogUtils.validateAutoCreateClose;
import static org.apache.paimon.options.CatalogOptions.CASE_SENSITIVE;
import static org.apache.paimon.options.CatalogOptions.WAREHOUSE;
import static org.apache.paimon.rest.RESTUtil.extractPrefixMap;
import static org.apache.paimon.rest.auth.AuthSession.createAuthSession;
import static org.apache.paimon.utils.ThreadPoolUtils.createScheduledThreadPool;

/** A catalog implementation for REST. */
public class RESTCatalog implements Catalog {

    public static final String HEADER_PREFIX = "header.";

    private final RESTClient client;
    private final ResourcePaths resourcePaths;
    private final AuthSession catalogAuth;
    private final CatalogContext context;
    private final boolean dataTokenEnabled;
    private final FileIO fileIO;

    private volatile ScheduledExecutorService refreshExecutor = null;

    public RESTCatalog(CatalogContext context) {
        this(context, true);
    }

    public RESTCatalog(CatalogContext context, boolean configRequired) {
        this.client = new HttpClient(context.options());
        this.catalogAuth = createAuthSession(context.options(), tokenRefreshExecutor());

        Options options = context.options();
        if (configRequired) {
            if (context.options().contains(WAREHOUSE)) {
                throw new IllegalArgumentException("Can not config warehouse in RESTCatalog.");
            }

            Map<String, String> initHeaders =
                    RESTUtil.merge(
                            extractPrefixMap(context.options(), HEADER_PREFIX),
                            catalogAuth.getHeaders());
            options =
                    new Options(
                            client.get(ResourcePaths.V1_CONFIG, ConfigResponse.class, initHeaders)
                                    .merge(context.options().toMap()));
        }

        context = CatalogContext.create(options, context.preferIO(), context.fallbackIO());
        this.context = context;
        this.resourcePaths = ResourcePaths.forCatalogProperties(options);

        this.dataTokenEnabled = options.get(RESTCatalogOptions.DATA_TOKEN_ENABLED);
        this.fileIO = dataTokenEnabled ? null : fileIOFromOptions(new Path(options.get(WAREHOUSE)));
    }

    @Override
    public Map<String, String> options() {
        return context.options().toMap();
    }

    @Override
    public RESTCatalogLoader catalogLoader() {
        return new RESTCatalogLoader(context);
    }

    @Override
    public List<String> listDatabases() {
        ListDatabasesResponse response =
                client.get(resourcePaths.databases(), ListDatabasesResponse.class, headers());
        if (response.getDatabases() != null) {
            return response.getDatabases();
        }
        return ImmutableList.of();
    }

    @Override
    public void createDatabase(String name, boolean ignoreIfExists, Map<String, String> properties)
            throws DatabaseAlreadyExistException {
        checkNotSystemDatabase(name);
        CreateDatabaseRequest request = new CreateDatabaseRequest(name, properties);
        try {
            client.post(
                    resourcePaths.databases(), request, CreateDatabaseResponse.class, headers());
        } catch (AlreadyExistsException e) {
            if (!ignoreIfExists) {
                throw new DatabaseAlreadyExistException(name);
            }
        } catch (ForbiddenException e) {
            throw new DatabaseNoPermissionException(name, e);
        }
    }

    @Override
    public Database getDatabase(String name) throws DatabaseNotExistException {
        if (isSystemDatabase(name)) {
            return Database.of(name);
        }
        try {
            GetDatabaseResponse response =
                    client.get(resourcePaths.database(name), GetDatabaseResponse.class, headers());
            return new Database.DatabaseImpl(
                    name, response.options(), response.comment().orElseGet(() -> null));
        } catch (NoSuchResourceException e) {
            throw new DatabaseNotExistException(name);
        } catch (ForbiddenException e) {
            throw new DatabaseNoPermissionException(name, e);
        }
    }

    @Override
    public void dropDatabase(String name, boolean ignoreIfNotExists, boolean cascade)
            throws DatabaseNotExistException, DatabaseNotEmptyException {
        checkNotSystemDatabase(name);
        try {
            if (!cascade && !this.listTables(name).isEmpty()) {
                throw new DatabaseNotEmptyException(name);
            }
            client.delete(resourcePaths.database(name), headers());
        } catch (NoSuchResourceException | DatabaseNotExistException e) {
            if (!ignoreIfNotExists) {
                throw new DatabaseNotExistException(name);
            }
        } catch (ForbiddenException e) {
            throw new DatabaseNoPermissionException(name, e);
        }
    }

    @Override
    public void alterDatabase(String name, List<PropertyChange> changes, boolean ignoreIfNotExists)
            throws DatabaseNotExistException {
        checkNotSystemDatabase(name);
        try {
            Pair<Map<String, String>, Set<String>> setPropertiesToRemoveKeys =
                    PropertyChange.getSetPropertiesToRemoveKeys(changes);
            Map<String, String> updateProperties = setPropertiesToRemoveKeys.getLeft();
            Set<String> removeKeys = setPropertiesToRemoveKeys.getRight();
            AlterDatabaseRequest request =
                    new AlterDatabaseRequest(new ArrayList<>(removeKeys), updateProperties);
            AlterDatabaseResponse response =
                    client.post(
                            resourcePaths.databaseProperties(name),
                            request,
                            AlterDatabaseResponse.class,
                            headers());
            if (response.getUpdated().isEmpty()) {
                throw new IllegalStateException("Failed to update properties");
            }
        } catch (NoSuchResourceException e) {
            if (!ignoreIfNotExists) {
                throw new DatabaseNotExistException(name);
            }
        } catch (ForbiddenException e) {
            throw new DatabaseNoPermissionException(name, e);
        }
    }

    @Override
    public List<String> listTables(String databaseName) throws DatabaseNotExistException {
        try {
            ListTablesResponse response =
                    client.get(
                            resourcePaths.tables(databaseName),
                            ListTablesResponse.class,
                            headers());
            if (response.getTables() != null) {
                return response.getTables();
            }
            return ImmutableList.of();
        } catch (NoSuchResourceException e) {
            throw new DatabaseNotExistException(databaseName);
        }
    }

    @Override
    public Table getTable(Identifier identifier) throws TableNotExistException {
        return CatalogUtils.loadTable(
                this,
                identifier,
                path -> fileIOForData(path, identifier),
                this::fileIOFromOptions,
                this::loadTableMetadata,
                new RESTSnapshotCommitFactory(catalogLoader()));
    }

    private FileIO fileIOForData(Path path, Identifier identifier) {
        return dataTokenEnabled
                ? new RESTTokenFileIO(catalogLoader(), this, identifier, path)
                : this.fileIO;
    }

    private FileIO fileIOFromOptions(Path path) {
        try {
            return FileIO.get(path, context);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected GetTableTokenResponse loadTableToken(Identifier identifier) {
        return client.get(
                resourcePaths.tableToken(identifier.getDatabaseName(), identifier.getObjectName()),
                GetTableTokenResponse.class,
                catalogAuth.getHeaders());
    }

    public boolean commitSnapshot(Identifier identifier, Snapshot snapshot) {
        CommitTableRequest request = new CommitTableRequest(identifier, snapshot);
        CommitTableResponse response =
                client.post(
                        resourcePaths.commitTable(identifier.getDatabaseName()),
                        request,
                        CommitTableResponse.class,
                        headers());
        return response.isSuccess();
    }

    private TableMetadata loadTableMetadata(Identifier identifier) throws TableNotExistException {
        GetTableResponse response;
        try {
            response =
                    client.get(
                            resourcePaths.table(
                                    identifier.getDatabaseName(), identifier.getTableName()),
                            GetTableResponse.class,
                            headers());
        } catch (NoSuchResourceException e) {
            throw new TableNotExistException(identifier);
        } catch (ForbiddenException e) {
            throw new TableNoPermissionException(identifier, e);
        }

        TableSchema schema = TableSchema.create(response.getSchemaId(), response.getSchema());
        return new TableMetadata(schema, response.isExternal(), response.getId());
    }

    @Override
    public void createTable(Identifier identifier, Schema schema, boolean ignoreIfExists)
            throws TableAlreadyExistException, DatabaseNotExistException {
        try {
            checkNotBranch(identifier, "createTable");
            checkNotSystemTable(identifier, "createTable");
            validateAutoCreateClose(schema.options());
            CreateTableRequest request = new CreateTableRequest(identifier, schema);
            client.post(resourcePaths.tables(identifier.getDatabaseName()), request, headers());
        } catch (AlreadyExistsException e) {
            if (!ignoreIfExists) {
                throw new TableAlreadyExistException(identifier);
            }
        } catch (NoSuchResourceException e) {
            throw new DatabaseNotExistException(identifier.getDatabaseName());
        } catch (BadRequestException e) {
            throw new RuntimeException(new IllegalArgumentException(e.getMessage()));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void renameTable(Identifier fromTable, Identifier toTable, boolean ignoreIfNotExists)
            throws TableNotExistException, TableAlreadyExistException {
        checkNotBranch(fromTable, "renameTable");
        checkNotBranch(toTable, "renameTable");
        checkNotSystemTable(fromTable, "renameTable");
        checkNotSystemTable(toTable, "renameTable");
        try {
            RenameTableRequest request = new RenameTableRequest(fromTable, toTable);
            client.post(resourcePaths.renameTable(fromTable.getDatabaseName()), request, headers());
        } catch (NoSuchResourceException e) {
            if (!ignoreIfNotExists) {
                throw new TableNotExistException(fromTable);
            }
        } catch (ForbiddenException e) {
            throw new TableNoPermissionException(fromTable, e);
        } catch (AlreadyExistsException e) {
            throw new TableAlreadyExistException(toTable);
        }
    }

    @Override
    public void alterTable(
            Identifier identifier, List<SchemaChange> changes, boolean ignoreIfNotExists)
            throws TableNotExistException, ColumnAlreadyExistException, ColumnNotExistException {
        checkNotSystemTable(identifier, "alterTable");
        try {
            AlterTableRequest request = new AlterTableRequest(changes);
            client.post(
                    resourcePaths.table(identifier.getDatabaseName(), identifier.getTableName()),
                    request,
                    headers());
        } catch (NoSuchResourceException e) {
            if (!ignoreIfNotExists) {
                if (e.resourceType() == ErrorResponseResourceType.TABLE) {
                    throw new TableNotExistException(identifier);
                } else if (e.resourceType() == ErrorResponseResourceType.COLUMN) {
                    throw new ColumnNotExistException(identifier, e.resourceName());
                }
            }
        } catch (AlreadyExistsException e) {
            throw new ColumnAlreadyExistException(identifier, e.resourceName());
        } catch (ForbiddenException e) {
            throw new TableNoPermissionException(identifier, e);
        } catch (NotImplementedException e) {
            throw new UnsupportedOperationException(e.getMessage());
        } catch (ServiceFailureException e) {
            throw new IllegalStateException(e.getMessage());
        } catch (BadRequestException e) {
            throw new RuntimeException(new IllegalArgumentException(e.getMessage()));
        }
    }

    @Override
    public void dropTable(Identifier identifier, boolean ignoreIfNotExists)
            throws TableNotExistException {
        checkNotBranch(identifier, "dropTable");
        checkNotSystemTable(identifier, "dropTable");
        try {
            client.delete(
                    resourcePaths.table(identifier.getDatabaseName(), identifier.getTableName()),
                    headers());
        } catch (NoSuchResourceException e) {
            if (!ignoreIfNotExists) {
                throw new TableNotExistException(identifier);
            }
        } catch (ForbiddenException e) {
            throw new TableNoPermissionException(identifier, e);
        }
    }

    @Override
    public void createPartitions(Identifier identifier, List<Map<String, String>> partitions)
            throws TableNotExistException {
        try {
            CreatePartitionsRequest request = new CreatePartitionsRequest(partitions);
            client.post(
                    resourcePaths.partitions(
                            identifier.getDatabaseName(), identifier.getTableName()),
                    request,
                    headers());
        } catch (NoSuchResourceException e) {
            throw new TableNotExistException(identifier);
        } catch (NotImplementedException ignored) {
            // not a metastore partitioned table
        }
    }

    @Override
    public void dropPartitions(Identifier identifier, List<Map<String, String>> partitions)
            throws TableNotExistException {
        try {
            DropPartitionsRequest request = new DropPartitionsRequest(partitions);
            client.post(
                    resourcePaths.dropPartitions(
                            identifier.getDatabaseName(), identifier.getTableName()),
                    request,
                    headers());
        } catch (NoSuchResourceException e) {
            throw new TableNotExistException(identifier);
        } catch (NotImplementedException ignored) {
            // not a metastore partitioned table
            FileStoreTable fileStoreTable = (FileStoreTable) getTable(identifier);
            try (FileStoreCommit commit =
                    fileStoreTable
                            .store()
                            .newCommit(
                                    createCommitUser(
                                            fileStoreTable.coreOptions().toConfiguration()))) {
                commit.dropPartitions(partitions, BatchWriteBuilder.COMMIT_IDENTIFIER);
            }
        }
    }

    @Override
    public void alterPartitions(Identifier identifier, List<Partition> partitions)
            throws TableNotExistException {
        try {
            AlterPartitionsRequest request = new AlterPartitionsRequest(partitions);
            client.post(
                    resourcePaths.alterPartitions(
                            identifier.getDatabaseName(), identifier.getTableName()),
                    request,
                    headers());
        } catch (NoSuchResourceException e) {
            throw new TableNotExistException(identifier);
        } catch (NotImplementedException ignored) {
            // not a metastore partitioned table
        }
    }

    @Override
    public void markDonePartitions(Identifier identifier, List<Map<String, String>> partitions)
            throws TableNotExistException {
        try {
            MarkDonePartitionsRequest request = new MarkDonePartitionsRequest(partitions);
            client.post(
                    resourcePaths.markDonePartitions(
                            identifier.getDatabaseName(), identifier.getTableName()),
                    request,
                    headers());
        } catch (NoSuchResourceException e) {
            throw new TableNotExistException(identifier);
        } catch (NotImplementedException ignored) {
            // not a metastore partitioned table
        }
    }

    @Override
    public List<Partition> listPartitions(Identifier identifier) throws TableNotExistException {
        try {
            ListPartitionsResponse response =
                    client.get(
                            resourcePaths.partitions(
                                    identifier.getDatabaseName(), identifier.getTableName()),
                            ListPartitionsResponse.class,
                            headers());
            if (response == null || response.getPartitions() == null) {
                return Collections.emptyList();
            }
            return response.getPartitions();
        } catch (NoSuchResourceException e) {
            throw new TableNotExistException(identifier);
        } catch (ForbiddenException e) {
            throw new TableNoPermissionException(identifier, e);
        } catch (NotImplementedException e) {
            // not a metastore partitioned table
            return listPartitionsFromFileSystem(getTable(identifier));
        }
    }

    @Override
    public View getView(Identifier identifier) throws ViewNotExistException {
        try {
            GetViewResponse response =
                    client.get(
                            resourcePaths.view(
                                    identifier.getDatabaseName(), identifier.getTableName()),
                            GetViewResponse.class,
                            headers());
            ViewSchema schema = response.getSchema();
            return new ViewImpl(
                    identifier,
                    schema.fields(),
                    schema.query(),
                    schema.dialects(),
                    schema.comment(),
                    schema.options());
        } catch (NoSuchResourceException e) {
            throw new ViewNotExistException(identifier);
        }
    }

    @Override
    public void dropView(Identifier identifier, boolean ignoreIfNotExists)
            throws ViewNotExistException {
        try {
            client.delete(
                    resourcePaths.view(identifier.getDatabaseName(), identifier.getTableName()),
                    headers());
        } catch (NoSuchResourceException e) {
            if (!ignoreIfNotExists) {
                throw new ViewNotExistException(identifier);
            }
        }
    }

    @Override
    public void createView(Identifier identifier, View view, boolean ignoreIfExists)
            throws ViewAlreadyExistException, DatabaseNotExistException {
        try {
            ViewSchema schema =
                    new ViewSchema(
                            view.rowType().getFields(),
                            view.query(),
                            view.dialects(),
                            view.comment().orElse(null),
                            view.options());
            CreateViewRequest request = new CreateViewRequest(identifier, schema);
            client.post(resourcePaths.views(identifier.getDatabaseName()), request, headers());
        } catch (NoSuchResourceException e) {
            throw new DatabaseNotExistException(identifier.getDatabaseName());
        } catch (AlreadyExistsException e) {
            if (!ignoreIfExists) {
                throw new ViewAlreadyExistException(identifier);
            }
        }
    }

    @Override
    public List<String> listViews(String databaseName) throws DatabaseNotExistException {
        try {
            ListViewsResponse response =
                    client.get(
                            resourcePaths.views(databaseName), ListViewsResponse.class, headers());
            return response.getViews();
        } catch (NoSuchResourceException e) {
            throw new DatabaseNotExistException(databaseName);
        }
    }

    @Override
    public void renameView(Identifier fromView, Identifier toView, boolean ignoreIfNotExists)
            throws ViewNotExistException, ViewAlreadyExistException {
        try {
            RenameTableRequest request = new RenameTableRequest(fromView, toView);
            client.post(resourcePaths.renameView(fromView.getDatabaseName()), request, headers());
        } catch (NoSuchResourceException e) {
            if (!ignoreIfNotExists) {
                throw new ViewNotExistException(fromView);
            }
        } catch (AlreadyExistsException e) {
            throw new ViewAlreadyExistException(toView);
        }
    }

    @Override
    public boolean caseSensitive() {
        return context.options().getOptional(CASE_SENSITIVE).orElse(true);
    }

    @Override
    public void close() throws Exception {
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
        }
        if (client != null) {
            client.close();
        }
    }

    private Map<String, String> headers() {
        return catalogAuth.getHeaders();
    }

    private ScheduledExecutorService tokenRefreshExecutor() {
        if (refreshExecutor == null) {
            synchronized (this) {
                if (refreshExecutor == null) {
                    this.refreshExecutor = createScheduledThreadPool(1, "token-refresh-thread");
                }
            }
        }

        return refreshExecutor;
    }
}
