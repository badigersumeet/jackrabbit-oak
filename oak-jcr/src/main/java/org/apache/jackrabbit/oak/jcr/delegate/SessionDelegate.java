/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.jcr.delegate;

import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.jcr.ItemExistsException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.ConstraintViolationException;

import com.google.common.collect.ImmutableMap;
import org.apache.jackrabbit.oak.api.AuthInfo;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.QueryEngine;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.jcr.observation.EventFactory;
import org.apache.jackrabbit.oak.jcr.session.RefreshStrategy;
import org.apache.jackrabbit.oak.jcr.session.SessionStats;
import org.apache.jackrabbit.oak.jcr.session.operation.SessionOperation;
import org.apache.jackrabbit.oak.plugins.identifier.IdentifierManager;
import org.apache.jackrabbit.oak.spi.security.SecurityProvider;
import org.apache.jackrabbit.oak.spi.security.authorization.AuthorizationConfiguration;
import org.apache.jackrabbit.oak.spi.security.authorization.permission.PermissionProvider;
import org.apache.jackrabbit.oak.stats.Clock;
import org.apache.jackrabbit.oak.stats.StatisticManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.jackrabbit.api.stats.RepositoryStatistics.Type.SESSION_READ_COUNTER;
import static org.apache.jackrabbit.api.stats.RepositoryStatistics.Type.SESSION_READ_DURATION;
import static org.apache.jackrabbit.api.stats.RepositoryStatistics.Type.SESSION_WRITE_COUNTER;
import static org.apache.jackrabbit.api.stats.RepositoryStatistics.Type.SESSION_WRITE_DURATION;
import static org.apache.jackrabbit.oak.commons.PathUtils.denotesRoot;

/**
 * TODO document
 */
public class SessionDelegate {
    static final Logger log = LoggerFactory.getLogger(SessionDelegate.class);
    static final Logger operationLogger = LoggerFactory.getLogger("org.apache.jackrabbit.oak.jcr.operations");

    private final ContentSession contentSession;
    private final SecurityProvider securityProvider;
    private final RefreshStrategy refreshStrategy;
    private boolean refreshAtNextAccess = false;

    /**
     * The repository-wide {@link ThreadLocal} that keeps track of the number
     * of saves performed in each thread.
     */
    private final ThreadLocal<Long> threadSaveCount;

    /**
     * Local copy of the {@link #threadSaveCount} for the current thread.
     * If the repository-wide counter differs from our local copy, then
     * some other session would have done a commit or this session is
     * being accessed from some other thread. In either case it's best to
     * refresh this session to avoid unexpected behaviour.
     */
    private long sessionSaveCount;

    private final Root root;
    private final IdentifierManager idManager;
    private final SessionStats sessionStats;

    private final Clock clock;

    // access time stamps and counters for statistics about this session
    private final long loginTime;
    private long accessTime;
    private long readTime = 0;
    private long writeTime = 0;
    private long refreshTime = 0;
    private long saveTime = 0;
    private long readCount = 0;
    private long writeCount = 0;
    private long refreshCount = 0;
    private long saveCount = 0;

    // repository-wide counters for statistics about all sessions
    private final AtomicLong readCounter;
    private final AtomicLong readDuration;
    private final AtomicLong writeCounter;
    private final AtomicLong writeDuration;

    private boolean isAlive = true;
    private int sessionOpCount;
    private long updateCount = 0;

    private String userData = null;

    private PermissionProvider permissionProvider;

    /**
     * The lock used to guarantee synchronized execution of repository
     * operations. An explicit lock is used instead of normal Java
     * synchronization in order to be able to log attempts to concurrently
     * use a session.
     */
    private final Lock lock = new ReentrantLock();

    /**
     * Create a new session delegate for a {@code ContentSession}. The refresh behaviour of the
     * session is governed by the value of the {@code refreshInterval} argument: if the session
     * has been idle longer than that value, an implicit refresh will take place.
     * In addition a refresh can always be scheduled from the next access by an explicit call
     * to {@link #refreshAtNextAccess()}. This is typically done from within the observation event
     * dispatcher in order.
     *
     * @param contentSession  the content session
     * @param securityProvider the security provider
     * @param refreshStrategy  the refresh strategy used for auto refreshing this session
     * @param statisticManager the statistics manager for tracking session operations
     */
    public SessionDelegate(
            @Nonnull ContentSession contentSession,
            @Nonnull SecurityProvider securityProvider,
            @Nonnull RefreshStrategy refreshStrategy,
            @Nonnull ThreadLocal<Long> threadSaveCount,
            @Nonnull StatisticManager statisticManager,
            @Nonnull Clock clock) {
        this.contentSession = checkNotNull(contentSession);
        this.securityProvider = checkNotNull(securityProvider);
        this.refreshStrategy = checkNotNull(refreshStrategy);
        this.threadSaveCount = checkNotNull(threadSaveCount);
        this.sessionSaveCount = getThreadSaveCount();
        this.root = contentSession.getLatestRoot();
        this.idManager = new IdentifierManager(root);
        this.sessionStats = new SessionStats(this);
        this.clock = checkNotNull(clock);
        this.loginTime = clock.getTime();
        this.accessTime = loginTime;
        checkNotNull(statisticManager);
        readCounter = statisticManager.getCounter(SESSION_READ_COUNTER);
        readDuration = statisticManager.getCounter(SESSION_READ_DURATION);
        writeCounter = statisticManager.getCounter(SESSION_WRITE_COUNTER);
        writeDuration = statisticManager.getCounter(SESSION_WRITE_DURATION);
    }

    @Nonnull
    public SessionStats getSessionStats() {
        return sessionStats;
    }

    private long getThreadSaveCount() {
        Long c = threadSaveCount.get();
        return c == null ? 0 : c;
    }

    public long getSecondsSinceLogin() {
        return SECONDS.convert(clock.getTime() - loginTime, MILLISECONDS);
    }

    public Date getLoginTime() {
        return new Date(loginTime);
    }

    private Date getTime(long timestamp) {
        if (timestamp != 0) {
            return new Date(timestamp);
        } else {
            return null;
        }
    }

    public Date getReadTime() {
        return getTime(readTime);
    }

    public Date getWriteTime() {
        return getTime(writeTime);
    }

    public Date getRefreshTime() {
        return getTime(refreshTime);
    }

    public Date getSaveTime() {
        return getTime(saveTime);
    }

    public long getReadCount() {
        return readCount;
    }

    public long getWriteCount() {
        return writeCount;
    }

    public long getRefreshCount() {
        return refreshCount;
    }

    public long getSaveCount() {
        return saveCount;
    }

    public void refreshAtNextAccess() {
        lock.lock();
        try {
            refreshAtNextAccess = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wrap the passed {@code iterator} in an iterator that synchronizes
     * all access to the underlying session.
     * @param iterator  iterator to synchronized
     * @param <T>
     * @return  synchronized iterator
     */
    public <T> Iterator<T> sync(Iterator<T> iterator) {
        return new SynchronizedIterator<T>(iterator);
    }

    /**
     * Performs the passed {@code SessionOperation} in a safe execution context. This
     * context ensures that the session is refreshed if necessary and that refreshing
     * occurs before the session operation is performed and the refreshing is done only
     * once.
     *
     * @param sessionOperation  the {@code SessionOperation} to perform
     * @param <T>  return type of {@code sessionOperation}
     * @return  the result of {@code sessionOperation.perform()}
     * @throws RepositoryException
     * @see #getRoot()
     */
    public <T> T perform(SessionOperation<T> sessionOperation)
            throws RepositoryException {
        long t0 = clock.getTime();

        // Acquire the exclusive lock for accessing session internals.
        // No other session should be holding the lock, so we log a
        // message to let the user know of such cases.
        if (!lock.tryLock()) {
            if (sessionOperation.isUpdate()) {
                Exception trace = new Exception(
                        "Stack trace of concurrent access to " + contentSession);
                log.warn("Attempt to perform " + sessionOperation + " while another thread is " +
                        "concurrently writing to " + contentSession + ". Blocking until the " +
                        "other thread is finished using this session. Please review your code " +
                        "to avoid concurrent use of a session.", trace);
            } else if (log.isDebugEnabled()) {
                Exception trace = new Exception(
                        "Stack trace of concurrent access to " + contentSession);
                log.warn("Attempt to perform " + sessionOperation + " while another thread is " +
                        "concurrently reading from " + contentSession + ". Blocking until the " +
                        "other thread is finished using this session. Please review your code " +
                        "to avoid concurrent use of a session.", trace);
            }
            lock.lock();
        }

        try {
            if (sessionOpCount == 0) {
                // Refresh and precondition checks only for non re-entrant
                // session operations. Don't refresh if this operation is a
                // refresh operation itself or a save operation, which does an
                // implicit refresh, or logout for obvious reasons.
                if (!sessionOperation.isRefresh()
                        && !sessionOperation.isSave()
                        && !sessionOperation.isLogout()
                        && (refreshAtNextAccess
                        || sessionSaveCount != getThreadSaveCount()
                        || refreshStrategy.needsRefresh(
                        SECONDS.convert(t0 - accessTime, MILLISECONDS)))) {
                    refresh(true);
                    refreshAtNextAccess = false;
                    sessionSaveCount = getThreadSaveCount();
                    updateCount++;
                }
                sessionOperation.checkPreconditions();
            }
            try {
                sessionOpCount++;
                T result = sessionOperation.perform();
                logOperationDetails(contentSession, sessionOperation);
                return result;
            } finally {
                accessTime = t0;
                long dt = NANOSECONDS.convert(clock.getTime() - t0, MILLISECONDS);
                sessionOpCount--;
                if (sessionOperation.isUpdate()) {
                    writeTime = t0;
                    writeCount++;
                    writeCounter.incrementAndGet();
                    writeDuration.addAndGet(dt);
                    updateCount++;
                } else {
                    readTime = t0;
                    readCount++;
                    readCounter.incrementAndGet();
                    readDuration.addAndGet(dt);
                }
                if (sessionOperation.isSave()) {
                    refreshAtNextAccess = false;
                    // Force refreshing on access through other sessions on the same thread
                    threadSaveCount.set(sessionSaveCount = (getThreadSaveCount() + 1));
                } else if (sessionOperation.isRefresh()) {
                    refreshAtNextAccess = false;
                    sessionSaveCount = getThreadSaveCount();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Same as {@link #perform(SessionOperation)} unless this method expects
     * {@link SessionOperation#perform} <em>not</em> to throw a {@code RepositoryException}.
     * Such exceptions will be wrapped into a {@code RuntimeException} and rethrown as they
     * are considered an internal error.
     *
     * @param sessionOperation  the {@code SessionOperation} to perform
     * @param <T>  return type of {@code sessionOperation}
     * @return  the result of {@code sessionOperation.perform()}
     * @see #getRoot()
     */
    public <T> T safePerform(SessionOperation<T> sessionOperation) {
        try {
            return perform(sessionOperation);
        } catch (RepositoryException e) {
            throw new RuntimeException("Unexpected exception thrown by operation " +
                    sessionOperation, e);
        }
    }

    @Nonnull
    public ContentSession getContentSession() {
        return contentSession;
    }

    /**
     * Determine whether this session is alive and has not been logged
     * out or become stale by other means.
     * @return {@code true} if this session is alive, {@code false} otherwise.
     */
    public boolean isAlive() {
        return isAlive;
    }

    /**
     * Check that this session is alive.
     * @throws RepositoryException if this session is not alive
     * @see #isAlive()
     */
    public void checkAlive() throws RepositoryException {
        if (!isAlive()) {
            throw new RepositoryException("This session has been closed.");
        }
    }

    /**
     * @return session update counter
     */
    public long getUpdateCount() {
        return updateCount;
    }

    public void setUserData(String userData) {
        this.userData = userData;
    }

    private void commit(Root root, String path) throws CommitFailedException {
        ImmutableMap.Builder<String, Object> info = ImmutableMap.builder();
        if (path != null && !denotesRoot(path)) {
            info.put(Root.COMMIT_PATH, path);
        }
        if (userData != null) {
            info.put(EventFactory.USER_DATA, userData);
        }
        root.commit(info.build());
        if (permissionProvider != null) {
            permissionProvider.refresh();
        }
    }

    /**
     * Commits the changes currently in the transient space.
     * TODO: Consolidate with save().
     *
     * @throws CommitFailedException if the commit failed
     */
    public void commit() throws CommitFailedException {
        commit(root, null);
    }

    /**
     * Commits the changes applied to the given root. The user data (if any)
     * currently attached to this session is passed as the commit message.
     * Used both for normal save() calls and for the various
     * direct-to-workspace operations.
     *
     * @throws CommitFailedException if the commit failed
     */
    public void commit(Root root) throws CommitFailedException {
        commit(root, null);
    }

    public void checkProtectedNode(String path) throws RepositoryException {
        NodeDelegate node = getNode(path);
        if (node == null) {
            throw new PathNotFoundException(
                    "Node " + path + " does not exist.");
        } else if (node.isProtected()) {
            throw new ConstraintViolationException(
                    "Node " + path + " is protected.");
        }
    }

    @Nonnull
    public AuthInfo getAuthInfo() {
        return contentSession.getAuthInfo();
    }

    public void logout() {
        if (!isAlive) {
            // ignore
            return;
        }

        isAlive = false;
        // TODO

        try {
            contentSession.close();
        } catch (IOException e) {
            log.warn("Error while closing connection", e);
        }
    }

    @Nonnull
    public IdentifierManager getIdManager() {
        return idManager;
    }

    @CheckForNull
    public NodeDelegate getRootNode() {
        return getNode("/");
    }

    /**
     * {@code NodeDelegate} at the given path
     * @param path Oak path
     * @return  The {@code NodeDelegate} at {@code path} or {@code null} if
     * none exists or not accessible.
     */
    @CheckForNull
    public NodeDelegate getNode(String path) {
        Tree tree = root.getTree(path);
        return tree.exists() ? new NodeDelegate(this, tree) : null;
    }

    /**
     * Returns the node or property delegate at the given path.
     *
     * @param path Oak path
     * @return node or property delegate, or {@code null} if none exists
     */
    @CheckForNull
    public ItemDelegate getItem(String path) {
        String name = PathUtils.getName(path);
        if (name.isEmpty()) {
            return getRootNode();
        } else {
            Tree parent = root.getTree(PathUtils.getParentPath(path));
            if (parent.hasProperty(name)) {
                return new PropertyDelegate(this, parent, name);
            }
            Tree child = parent.getChild(name);
            if (child.exists()) {
                return new NodeDelegate(this, child);
            } else {
                return null;
            }
        }
    }

    @CheckForNull
    public NodeDelegate getNodeByIdentifier(String id) {
        Tree tree = idManager.getTree(id);
        return (tree == null || !tree.exists()) ? null : new NodeDelegate(this, tree);
    }

    /**
     * {@code PropertyDelegate} at the given path
     * @param path Oak path
     * @return  The {@code PropertyDelegate} at {@code path} or {@code null} if
     * none exists or not accessible.
     */
    @CheckForNull
    public PropertyDelegate getProperty(String path) {
        Tree parent = root.getTree(PathUtils.getParentPath(path));
        String name = PathUtils.getName(path);
        return parent.hasProperty(name) ? new PropertyDelegate(this, parent,
                name) : null;
    }

    public boolean hasPendingChanges() {
        return root.hasPendingChanges();
    }

    /**
     * Save the subtree rooted at the given {@code path}, or the entire
     * transient space if given the root path or {@code null}.
     * <p>
     * This implementation only performs the save if the subtree rooted
     * at {@code path} contains all transient changes and will throw an
     * {@link javax.jcr.UnsupportedRepositoryOperationException} otherwise.
     *
     * @param path
     * @throws RepositoryException
     */
    public void save(String path) throws RepositoryException {
        saveTime = clock.getTime();
        saveCount++;
        try {
            commit(root, path);
        } catch (CommitFailedException e) {
            RepositoryException repositoryException = newRepositoryException(e);
            sessionStats.failedSave(repositoryException);
            throw repositoryException;
        }
    }

    public void refresh(boolean keepChanges) {
        refreshTime = clock.getTime();
        refreshCount++;
        if (keepChanges && hasPendingChanges()) {
            root.rebase();
        } else {
            root.refresh();
        }
        if (permissionProvider != null) {
            permissionProvider.refresh();
        }
    }

    //----------------------------------------------------------< Workspace >---

    @Nonnull
    public String getWorkspaceName() {
        return contentSession.getWorkspaceName();
    }

    /**
     * Move a node
     *
     * @param srcPath  oak path to the source node to copy
     * @param destPath  oak path to the destination
     * @param transientOp  whether or not to perform the move in transient space
     * @throws RepositoryException
     */
    public void move(String srcPath, String destPath, boolean transientOp)
            throws RepositoryException {

        Root moveRoot = transientOp ? root : contentSession.getLatestRoot();

        // check destination
        Tree dest = moveRoot.getTree(destPath);
        if (dest.exists()) {
            throw new ItemExistsException(destPath);
        }

        // check parent of destination
        String destParentPath = PathUtils.getParentPath(destPath);
        Tree destParent = moveRoot.getTree(destParentPath);
        if (!destParent.exists()) {
            throw new PathNotFoundException(PathUtils.getParentPath(destPath));
        }

        // check source exists
        Tree src = moveRoot.getTree(srcPath);
        if (!src.exists()) {
            throw new PathNotFoundException(srcPath);
        }

        try {
            if (!moveRoot.move(srcPath, destPath)) {
                throw new RepositoryException("Cannot move node at " + srcPath + " to " + destPath);
            }
            if (!transientOp) {
                saveTime = clock.getTime();
                saveCount++;
                commit(moveRoot);
                refresh(true);
            }
        } catch (CommitFailedException e) {
            throw newRepositoryException(e);
        }
    }

    @Nonnull
    public QueryEngine getQueryEngine() {
        return root.getQueryEngine();
    }

    @Nonnull
    public PermissionProvider getPermissionProvider() {
        if (permissionProvider == null) {
            permissionProvider = checkNotNull(securityProvider)
                    .getConfiguration(AuthorizationConfiguration.class)
                    .getPermissionProvider(root, getWorkspaceName(), getAuthInfo().getPrincipals());
        }
        return permissionProvider;
    }

    /**
     * The current {@code Root} instance this session delegate instance operates on.
     * To ensure the returned root reflects the correct repository revision access
     * should only be done from within a {@link SessionOperation} closure through
     * {@link #perform(SessionOperation)}.
     *
     * @return  current root
     */
    @Nonnull
    public Root getRoot() {
        return root;
    }

    @Override
    public String toString() {
        return contentSession.toString();
    }

    //------------------------------------------------------------< internal >---

    private static <T> void logOperationDetails(ContentSession session, SessionOperation<T> ops) {
        if (operationLogger.isDebugEnabled()){
            Marker sessionMarker = MarkerFactory.getMarker(session.toString());
            String sessionId = session.toString();
            operationLogger.debug(sessionMarker, String.format("[%s] %s", sessionId, ops));
        }
    }


    /**
     * Wraps the given {@link CommitFailedException} instance using the
     * appropriate {@link RepositoryException} subclass based on the
     * {@link CommitFailedException#getType() type} of the given exception.
     *
     * @param exception typed commit failure exception
     * @return matching repository exception
     */
    private static RepositoryException newRepositoryException(CommitFailedException exception) {
        return exception.asRepositoryException();
    }

    //------------------------------------------------------------< SynchronizedIterator >---

    /**
     * This iterator delegates to a backing iterator and synchronises
     * all calls to the backing iterator on this {@code SessionDelegate}
     * instance.
     *
     * @param <T>
     */
    private final class SynchronizedIterator<T> implements Iterator<T> {
        private final Iterator<T> iterator;

        SynchronizedIterator(Iterator<T> iterator) {
            this.iterator = iterator;
        }

        @Override
        public boolean hasNext() {
            lock.lock();
            try {
                return iterator.hasNext();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public T next() {
            lock.lock();
            try {
                return iterator.next();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void remove() {
            lock.lock();
            try {
                iterator.remove();
            } finally {
                lock.unlock();
            }
        }
    }

}
