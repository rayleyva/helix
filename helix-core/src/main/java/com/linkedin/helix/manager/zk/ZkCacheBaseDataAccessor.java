package com.linkedin.helix.manager.zk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import org.I0Itec.zkclient.DataUpdater;
import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;

import com.linkedin.helix.BaseDataAccessor;
import com.linkedin.helix.manager.zk.ZkAsyncCallbacks.CreateCallbackHandler;
import com.linkedin.helix.manager.zk.ZkBaseDataAccessor.RetCode;
import com.linkedin.helix.store.HelixPropertyListener;
import com.linkedin.helix.store.zk.ZNode;

public class ZkCacheBaseDataAccessor<T> implements BaseDataAccessor<T>
{
  private static final Logger LOG        =
                                             Logger.getLogger(ZkCacheBaseDataAccessor.class);

  final WriteThroughCache<T>  _wtCache;
  protected final ZkCallbackCache<T>    _zkCache;

  final ZkBaseDataAccessor<T> _baseAccessor;
  final Map<String, Cache<T>> _cacheMap;
  final String                _chrootPath;

  // fire listeners
  private final ReentrantLock _eventLock = new ReentrantLock();
  private ZkCacheEventThread  _eventThread;

  public ZkCacheBaseDataAccessor(ZkBaseDataAccessor<T> baseAccessor,
                                 List<String> wtCachePaths)
  {
    this(baseAccessor, null, wtCachePaths, null);
  }

  public ZkCacheBaseDataAccessor(ZkBaseDataAccessor<T> baseAccessor,
                                 String chrootPath,
                                 List<String> wtCachePaths,
                                 List<String> zkCachePaths)
  {
    LOG.info("START: Init ZkCacheBaseDataAccessor: " + chrootPath + ", " + wtCachePaths
        + ", " + zkCachePaths);
    if (chrootPath == null || chrootPath.equals("/"))
    {
      _chrootPath = null;
    }
    else
    {
      PathUtils.validatePath(chrootPath);
      _chrootPath = chrootPath;
    }
    _baseAccessor = baseAccessor;

    start();
    _wtCache = new WriteThroughCache<T>(baseAccessor, wtCachePaths);
    _zkCache = new ZkCallbackCache<T>(baseAccessor, chrootPath, zkCachePaths, _eventThread);

    // TODO: need to make sure no overlap between wtCachePaths and zkCachePaths
    // TreeMap key is ordered by key string length, so more general (i.e. short) prefix
    // comes first
    _cacheMap = new TreeMap<String, Cache<T>>(new Comparator<String>()
    {
      @Override
      public int compare(String o1, String o2)
      {
        int len1 = o1.split("/").length;
        int len2 = o2.split("/").length;
        return len1 - len2;
      }
    });

    if (wtCachePaths != null && !wtCachePaths.isEmpty())
    {
      for (String path : wtCachePaths)
      {
        _cacheMap.put(path, _wtCache);
      }
    }

    if (zkCachePaths != null && !zkCachePaths.isEmpty())
    {
      for (String path : zkCachePaths)
      {
        _cacheMap.put(path, _zkCache);
      }
    }
  }

  private String prependChroot(String clientPath)
  {
    if (_chrootPath != null)
    {
      // handle clientPath = "/"
      if (clientPath.length() == 1)
      {
        return _chrootPath;
      }
      return _chrootPath + clientPath;
    }
    else
    {
      return clientPath;
    }
  }

  private List<String> prependChroot(List<String> clientPaths)
  {
    List<String> serverPaths = new ArrayList<String>();
    for (String clientPath : clientPaths)
    {
      serverPaths.add(prependChroot(clientPath));
    }
    return serverPaths;
  }

  /*
   * find the first path in paths that is a descendant
   */
  private String firstCachePath(List<String> paths)
  {
    for (String cachePath : _cacheMap.keySet())
    {
      for (String path : paths)
      {
        if (path.startsWith(cachePath))
        {
          return path;
        }
      }
    }
    return null;
  }

  private Cache<T> getCache(String path)
  {
    for (String cachePath : _cacheMap.keySet())
    {
      if (path.startsWith(cachePath))
      {
        return _cacheMap.get(cachePath);
      }
    }

    return null;
  }

  private Cache<T> getCache(List<String> paths)
  {
    Cache<T> cache = null;
    for (String path : paths)
    {
      for (String cachePath : _cacheMap.keySet())
      {
        if (cache == null && path.startsWith(cachePath))
        {
          cache = _cacheMap.get(cachePath);
        }
        else if (cache != null && cache != _cacheMap.get(cachePath))
        {
          throw new IllegalArgumentException("Couldn't do cross-cache async operations. paths: "
              + paths);
        }
      }
    }

    return cache;
  }

  private void updateCache(Cache<T> cache,
                           List<String> createPaths,
                           boolean success,
                           String updatePath,
                           T data,
                           Stat stat)
  {
    if (createPaths == null || createPaths.isEmpty())
    {
      if (success)
      {
        cache.update(updatePath, data, stat);
      }
    }
    else
    {
      String firstPath = firstCachePath(createPaths);
      if (firstPath != null)
      {
        cache.updateRecursive(firstPath);
      }
    }
  }

  @Override
  public boolean create(String path, T data, int options)
  {
    String clientPath = path;
    String serverPath = prependChroot(clientPath);

    Cache<T> cache = getCache(serverPath);
    if (cache != null)
    {
      try
      {
        cache.lockWrite();
        List<String> pathsCreated = new ArrayList<String>();
        RetCode rc = _baseAccessor.create(serverPath, data, pathsCreated, options);
        boolean success = (rc == RetCode.OK);

        updateCache(cache, pathsCreated, success, serverPath, data, ZNode.ZERO_STAT);

        return success;
      }
      finally
      {
        cache.unlockWrite();
      }
    }

    // no cache
    return _baseAccessor.create(serverPath, data, options);
  }

  @Override
  public boolean set(String path, T data, int options)
  {
    String clientPath = path;
    String serverPath = prependChroot(clientPath);

    Cache<T> cache = getCache(serverPath);
    if (cache != null)
    {
      try
      {
        cache.lockWrite();
        Stat setStat = new Stat();
        List<String> pathsCreated = new ArrayList<String>();
        boolean success =
            _baseAccessor.set(serverPath, data, pathsCreated, setStat, options);

        updateCache(cache, pathsCreated, success, serverPath, data, setStat);

        return success;
      }
      finally
      {
        cache.unlockWrite();
      }
    }

    // no cache
    return _baseAccessor.set(serverPath, data, options);
  }

  @Override
  public boolean update(String path, DataUpdater<T> updater, int options)
  {
    String clientPath = path;
    String serverPath = prependChroot(clientPath);

    Cache<T> cache = getCache(serverPath);
    if (cache != null)
    {
      try
      {
        cache.lockWrite();
        Stat setStat = new Stat();
        List<String> pathsCreated = new ArrayList<String>();
        T updateData =
            _baseAccessor.update(serverPath, updater, pathsCreated, setStat, options);
        boolean success = (updateData != null);
        updateCache(cache, pathsCreated, success, serverPath, updateData, setStat);

        return success;
      }
      finally
      {
        cache.unlockWrite();
      }
    }

    // no cache
    return _baseAccessor.update(serverPath, updater, options);
  }

  @Override
  public boolean exists(String path, int options)
  {
    String clientPath = path;
    String serverPath = prependChroot(clientPath);

    Cache<T> cache = getCache(serverPath);
    if (cache != null)
    {
      boolean exists = cache.exists(serverPath);
      if (exists)
      {
        return true;
      }
    }

    // if not exists in cache, always fall back to zk
    return _baseAccessor.exists(serverPath, options);
  }

  @Override
  public boolean remove(String path, int options)
  {
    String clientPath = path;
    String serverPath = prependChroot(clientPath);

    Cache<T> cache = getCache(serverPath);
    if (cache != null)
    {
      try
      {
        cache.lockWrite();

        boolean success = _baseAccessor.remove(serverPath, options);
        if (success)
        {
          cache.purgeRecursive(serverPath);
        }

        return success;
      }
      finally
      {
        cache.unlockWrite();
      }
    }

    // no cache
    return _baseAccessor.remove(serverPath, options);
  }

  @Override
  public T get(String path, Stat stat, int options)
  {
    String clientPath = path;
    String serverPath = prependChroot(clientPath);

    Cache<T> cache = getCache(serverPath);
    if (cache != null)
    {
      T record = null;
      ZNode znode = cache.get(serverPath);

      if (znode != null)
      {
        // TODO: shall return a deep copy instead of reference
        record = ((T) znode.getData());
        if (stat != null)
        {
          DataTree.copyStat(znode.getStat(), stat);
        }
        return record;

      }
      else
      {
        // if cache miss, fall back to zk and update cache
        try
        {
          cache.lockWrite();
          record = _baseAccessor.get(serverPath, stat, options);
          cache.update(serverPath, record, stat);
        }
        // throw ZkNoNodeException if on non-exist
        finally
        {
          cache.unlockWrite();
        }

        return record;
      }
    }

    // no cache
    return _baseAccessor.get(serverPath, stat, options);
  }

  @Override
  public Stat getStat(String path, int options)
  {
    String clientPath = path;
    String serverPath = prependChroot(clientPath);

    Cache<T> cache = getCache(serverPath);
    if (cache != null)
    {
      Stat stat = new Stat();
      ZNode znode = cache.get(serverPath);

      if (znode != null)
      {
        return znode.getStat();

      }
      else
      {
        // if cache miss, fall back to zk and update cache
        try
        {
          cache.lockWrite();
          T data = _baseAccessor.get(serverPath, stat, options);
          cache.update(serverPath, data, stat);
        }
        catch (ZkNoNodeException e)
        {
          return null;
        }
        finally
        {
          cache.unlockWrite();
        }

        return stat;
      }
    }

    // no cache
    return _baseAccessor.getStat(serverPath, options);
  }

  @Override
  public boolean[] createChildren(List<String> paths, List<T> records, int options)
  {
    final int size = paths.size();
    List<String> serverPaths = prependChroot(paths);

    Cache<T> cache = getCache(serverPaths);
    if (cache != null)
    {
      try
      {
        cache.lockWrite();
        boolean[] needCreate = new boolean[size];
        Arrays.fill(needCreate, true);
        List<List<String>> pathsCreatedList =
            new ArrayList<List<String>>(Collections.<List<String>> nCopies(size, null));
        CreateCallbackHandler[] createCbList =
            _baseAccessor.create(serverPaths,
                                 records,
                                 needCreate,
                                 pathsCreatedList,
                                 options);

        boolean[] success = new boolean[size];
        for (int i = 0; i < size; i++)
        {
          CreateCallbackHandler cb = createCbList[i];
          success[i] = (Code.get(cb.getRc()) == Code.OK);

          updateCache(cache,
                      pathsCreatedList.get(i),
                      success[i],
                      serverPaths.get(i),
                      records.get(i),
                      ZNode.ZERO_STAT);
        }

        return success;
      }
      finally
      {
        cache.unlockWrite();
      }
    }

    // no cache
    return _baseAccessor.createChildren(serverPaths, records, options);
  }

  @Override
  public boolean[] setChildren(List<String> paths, List<T> records, int options)
  {
    final int size = paths.size();
    List<String> serverPaths = prependChroot(paths);

    Cache<T> cache = getCache(serverPaths);
    if (cache != null)
    {
      try
      {
        cache.lockWrite();
        List<Stat> setStats = new ArrayList<Stat>();
        List<List<String>> pathsCreatedList =
            new ArrayList<List<String>>(Collections.<List<String>> nCopies(size, null));
        boolean[] success =
            _baseAccessor.set(serverPaths, records, pathsCreatedList, setStats, options);

        for (int i = 0; i < size; i++)
        {
          updateCache(cache,
                      pathsCreatedList.get(i),
                      success[i],
                      serverPaths.get(i),
                      records.get(i),
                      setStats.get(i));
        }

        return success;
      }
      finally
      {
        cache.unlockWrite();
      }
    }

    return _baseAccessor.setChildren(serverPaths, records, options);
  }

  @Override
  public boolean[] updateChildren(List<String> paths,
                                  List<DataUpdater<T>> updaters,
                                  int options)
  {
    final int size = paths.size();
    List<String> serverPaths = prependChroot(paths);

    Cache<T> cache = getCache(serverPaths);
    if (cache != null)
    {
      try
      {
        cache.lockWrite();

        List<Stat> setStats = new ArrayList<Stat>();
        boolean[] success = new boolean[size];
        List<List<String>> pathsCreatedList =
            new ArrayList<List<String>>(Collections.<List<String>> nCopies(size, null));
        List<T> updateData =
            _baseAccessor.update(serverPaths,
                                 updaters,
                                 pathsCreatedList,
                                 setStats,
                                 options);

        // System.out.println("updateChild: ");
        // for (T data : updateData)
        // {
        // System.out.println(data);
        // }

        for (int i = 0; i < size; i++)
        {
          success[i] = (updateData.get(i) != null);
          updateCache(cache,
                      pathsCreatedList.get(i),
                      success[i],
                      serverPaths.get(i),
                      updateData.get(i),
                      setStats.get(i));
        }
        return success;
      }
      finally
      {
        cache.unlockWrite();
      }
    }

    // no cache
    return _baseAccessor.updateChildren(serverPaths, updaters, options);
  }

  // TODO: change to use async_exists
  @Override
  public boolean[] exists(List<String> paths, int options)
  {
    final int size = paths.size();
    List<String> serverPaths = prependChroot(paths);

    boolean exists[] = new boolean[size];
    for (int i = 0; i < size; i++)
    {
      exists[i] = exists(serverPaths.get(i), options);
    }
    return exists;
  }

  @Override
  public boolean[] remove(List<String> paths, int options)
  {
    final int size = paths.size();
    List<String> serverPaths = prependChroot(paths);

    Cache<T> cache = getCache(serverPaths);
    if (cache != null)
    {
      try
      {
        cache.lockWrite();

        boolean[] success = _baseAccessor.remove(serverPaths, options);

        for (int i = 0; i < size; i++)
        {
          if (success[i])
          {
            cache.purgeRecursive(serverPaths.get(i));
          }
        }
        return success;
      }
      finally
      {
        cache.unlockWrite();
      }
    }

    // no cache
    return _baseAccessor.remove(serverPaths, options);
  }

  @Override
  public List<T> get(List<String> paths, List<Stat> stats, int options)
  {
    if (paths == null || paths.isEmpty())
    {
      return Collections.emptyList();
    }

    final int size = paths.size();
    List<String> serverPaths = prependChroot(paths);

    List<T> records = new ArrayList<T>(Collections.<T> nCopies(size, null));
    List<Stat> readStats = new ArrayList<Stat>(Collections.<Stat> nCopies(size, null));

    boolean needRead = false;
    boolean needReads[] = new boolean[size]; // init to false

    Cache<T> cache = getCache(serverPaths);
    if (cache != null)
    {
      try
      {
        cache.lockRead();
        for (int i = 0; i < size; i++)
        {
          ZNode zNode = cache.get(serverPaths.get(i));
          if (zNode != null)
          {
            // TODO: shall return a deep copy instead of reference
            records.set(i, (T) zNode.getData());
            readStats.set(i, zNode.getStat());
          }
          else
          {
            needRead = true;
            needReads[i] = true;
          }
        }
      }
      finally
      {
        cache.unlockRead();
      }

      // cache miss, fall back to zk and update cache
      if (needRead)
      {
        cache.lockWrite();
        try
        {
          List<T> readRecords = _baseAccessor.get(serverPaths, readStats, needReads);
          for (int i = 0; i < size; i++)
          {
            if (needReads[i])
            {
              records.set(i, readRecords.get(i));
              cache.update(serverPaths.get(i), readRecords.get(i), readStats.get(i));
            }
          }
        }
        finally
        {
          cache.unlockWrite();
        }
      }

      if (stats != null)
      {
        stats.clear();
        stats.addAll(readStats);
      }

      return records;
    }

    // no cache
    return _baseAccessor.get(serverPaths, stats, options);
  }

  // TODO: add cache
  @Override
  public Stat[] getStats(List<String> paths, int options)
  {
    List<String> serverPaths = prependChroot(paths);
    return _baseAccessor.getStats(serverPaths, options);
  }

  @Override
  public List<String> getChildNames(String parentPath, int options)
  {
    String serverParentPath = prependChroot(parentPath);

    Cache<T> cache = getCache(serverParentPath);
    if (cache != null)
    {
      // System.out.println("zk-cache");
      ZNode znode = cache.get(serverParentPath);

      if (znode != null && znode.getChildSet() != Collections.<String> emptySet())
      {
        // System.out.println("zk-cache-hit: " + parentPath);
        List<String> childNames = new ArrayList<String>(znode.getChildSet());
        Collections.sort(childNames);
        return childNames;
      }
      else
      {
        // System.out.println("zk-cache-miss");
        try
        {
          cache.lockWrite();

          List<String> childNames =
              _baseAccessor.getChildNames(serverParentPath, options);
          // System.out.println("\t--" + childNames);
          cache.addToParentChildSet(serverParentPath, childNames);

          return childNames;
        }
        finally
        {
          cache.unlockWrite();
        }
      }
    }

    // no cache
    return _baseAccessor.getChildNames(serverParentPath, options);
  }

  @Override
  public List<T> getChildren(String parentPath, List<Stat> stats, int options)
  {
    List<String> childNames = getChildNames(parentPath, options);
    if (childNames == null)
    {
      return null;
    }

    List<String> paths = new ArrayList<String>();
    for (String childName : childNames)
    {
      String path = parentPath + "/" + childName;
      paths.add(path);
    }

    return get(paths, stats, options);
  }

  @Override
  public void subscribeDataChanges(String path, IZkDataListener listener)
  {
    String serverPath = prependChroot(path);

    _baseAccessor.subscribeDataChanges(serverPath, listener);
  }

  @Override
  public void unsubscribeDataChanges(String path, IZkDataListener listener)
  {
    String serverPath = prependChroot(path);

    _baseAccessor.unsubscribeDataChanges(serverPath, listener);
  }

  @Override
  public List<String> subscribeChildChanges(String path, IZkChildListener listener)
  {
    String serverPath = prependChroot(path);

    return _baseAccessor.subscribeChildChanges(serverPath, listener);
  }

  @Override
  public void unsubscribeChildChanges(String path, IZkChildListener listener)
  {
    String serverPath = prependChroot(path);

    _baseAccessor.unsubscribeChildChanges(serverPath, listener);
  }

  public void subscribe(String parentPath, HelixPropertyListener listener)
  {
    String serverPath = prependChroot(parentPath);
    _zkCache.subscribe(serverPath, listener);
  }

  public void unsubscribe(String parentPath, HelixPropertyListener listener)
  {
    String serverPath = prependChroot(parentPath);
    _zkCache.unsubscribe(serverPath, listener);
  }

  void start()
  {
    try
    {
      _eventLock.lockInterruptibly();
      if (_eventThread != null)
      {
        LOG.warn(_eventThread + " has already started");
        return;
      }
      
      LOG.debug("Starting ZkCacheEventThread...");

      _eventThread = new ZkCacheEventThread("");
      _eventThread.start();
    }
    catch (InterruptedException e)
    {
      LOG.error("Current thread is interrupted when starting ZkCacheEventThread. ", e);
    }
    finally
    {
      _eventLock.unlock();
    }

    LOG.debug("Start ZkCacheEventThread...done");
  }

  public void stop()
  {
    try
    {
      _eventLock.lockInterruptibly();
      
      if (_eventThread == null)
      {
        LOG.warn(_eventThread + " has already stopped");
        return;
      }
      
      LOG.debug("Stopping ZkCacheEventThread...");
      _eventThread.interrupt();
      _eventThread.join(2000);
      _eventThread = null;
    }
    catch (InterruptedException e)
    {
      LOG.error("Current thread is interrupted when stopping ZkCacheEventThread.");
    }
    finally
    {
      _eventLock.unlock();
    }
    
    LOG.debug("Stop ZkCacheEventThread...done");

  }
}
