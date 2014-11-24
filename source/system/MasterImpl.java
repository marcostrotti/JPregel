/**
 * 
 */
package system;

import exceptions.DataNotFoundException;
import exceptions.IllegalClassException;
import exceptions.IllegalInputException;
import exceptions.NoResourcesException;
import graphs.GraphPartition;
import graphs.GraphPartitioner;

import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import utility.JPregelLogger;
import utility.Pair;
import api.Vertex;

/**
 * @author Manasa Chandrasekhar
 * @author Kowshik Prakasam
 * 
 */
public class MasterImpl extends UnicastRemoteObject implements ManagerToMaster,
		 Runnable {

	/**
	 * 
	 */
	private int superStep;

	public int getSuperStep() {
		return superStep;
	}

	public void setSuperStep(int superStep) {
		this.superStep = superStep;
	}

	private static final long serialVersionUID = -7962409918852099855L;

	private GraphPartitioner gp;
	private Thread superstepExecutorThread;
	private Thread faultDetectorThread;
	public Map<String, WorkerManager> idManagerMap;
	private Logger logger;
	private String id;
	private String vertexClassName;
	private List<String> returnedManagers;
	private boolean allDone;
	
	private float initTime,endTime;

	private int participatingMgrs;

	public synchronized int getParticipatingMgrs() {
		return participatingMgrs;
	}

	public synchronized void setParticipatingMgrs(int participatingMgrs) {
		this.participatingMgrs = participatingMgrs;
	}

	private int numMachines;

	private FaultDetector aFaultDetector;

	private boolean isActive;

	private boolean isWkrMgrsInitialized;

	private int lastCheckPoint;

	public String getVertexClassName() {
		return vertexClassName;
	}

	public void setVertexClassName(String vertexClassName) {
		this.vertexClassName = vertexClassName;
		logger.info("set vertexClassName : " + vertexClassName);
	}

	private static final String LOG_FILE_PREFIX = JPregelConstants.LOG_DIR
			+ "master";
	private static final String LOG_FILE_SUFFIX = ".log";
	private static final int PORT_NUMBER = 3672;

	public class FaultDetector implements Runnable {

		private Logger logger;
		private static final String LOG_FILE_PREFIX = JPregelConstants.LOG_DIR
				+ "faultdetector";
		private static final String LOG_FILE_SUFFIX = ".log";

		private void initLogger() throws IOException {
			this.logger = JPregelLogger.getLogger(this.getID(), LOG_FILE_PREFIX
					+ LOG_FILE_SUFFIX);
		}

		public FaultDetector() throws IOException {
			initLogger();
		}

		@Override
		public void run() {
			while (true) {
				WorkerManager aWkrMgr = null;
				String wkrMgrID = null;
				try {
					for (Map.Entry<String, WorkerManager> e : idManagerMap
							.entrySet()) {
						wkrMgrID = e.getKey();
						aWkrMgr = e.getValue();
						aWkrMgr.isAlive();
					}
				} catch (RemoteException e) {
					this.logger
							.severe("Worker manager : " + wkrMgrID + " died");
					System.err.println("Worker manager : "+wkrMgrID+" died");
					e.printStackTrace();
					System.err.println("Deactivating master");
					// deactivate master
					deactivate();

					synchronized (idManagerMap) {
						idManagerMap.remove(wkrMgrID);
					}

					// check if this fellow has returned already. decrement
					// counter otherwise.
					if (!returnedManagers.contains(wkrMgrID)) {
						setParticipatingMgrs(getParticipatingMgrs() - 1);
						this.logger
								.info(wkrMgrID
										+ " didn't report completion earlier .. so decrementing participatingMgrs to "
										+ participatingMgrs);
						System.err.println(wkrMgrID
								+ " didn't report completion earlier .. so decrementing participatingMgrs to "
								+ participatingMgrs);
						if (getParticipatingMgrs() == 0) {
							allDone = true;
						}
					}
					// for all worker managers, other than the failed
					// manager,
					// stop the superstep immediately.
					
					stopSuperStep();

					// for all worker managers, other than the failed
					// manager,
					// restore state.
					try {
						restoreState();
					} catch (IOException e1) {

						e1.printStackTrace();
					} catch (IllegalInputException e1) {

						e1.printStackTrace();
					} catch (DataNotFoundException e1) {

						e1.printStackTrace();
					} catch (InstantiationException e1) {

						e1.printStackTrace();
					} catch (IllegalAccessException e1) {

						e1.printStackTrace();
					} catch (ClassNotFoundException e1) {

						e1.printStackTrace();
					} catch (NoResourcesException e1) {

						e1.printStackTrace();
					}
					
					//activating master again
					setSuperStep(getLastCheckPoint());
					setWkrMgrsInitialized(true);
					activate();

				}
				
				
			}
		}

		public String getID() {
			return "FaultDetector";
		}
	}

	/**
	 * @return
	 */
	private int getLastCheckPoint() {
		return this.lastCheckPoint;
	}

	/**
	 * @return
	 */
	private void setLastCheckPoint(int checkPoint) {
		this.lastCheckPoint = checkPoint;
	}

	public MasterImpl(String vertexClassName, int numMachines)
			throws IOException {

		this.setId("Master");
		initLogger();
		this.lastCheckPoint = JPregelConstants.FIRST_SUPERSTEP;
		this.setNumMachines(numMachines);
		this.setSuperStep(JPregelConstants.FIRST_SUPERSTEP);
		this.setVertexClassName(vertexClassName);
		this.returnedManagers = new Vector<String>();
		this.idManagerMap = new HashMap<String, WorkerManager>();
		this.superstepExecutorThread = new Thread(this, getId());
		this.aFaultDetector = new FaultDetector();
		this.faultDetectorThread = new Thread(this.aFaultDetector,this.aFaultDetector.getID());

	}

	/**
	 * @param numMachines
	 */
	private void setNumMachines(int numMachines) {
		this.numMachines = numMachines;

	}


	public void setId(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	private void initLogger() throws IOException {
		this.logger = JPregelLogger.getLogger(getId(), LOG_FILE_PREFIX
				+ LOG_FILE_SUFFIX);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.ManagerToMaster#register(system.WorkerManager,
	 * java.lang.String)
	 */
	@Override
	public synchronized void register(WorkerManager aWorkerManager, String id)
			throws RemoteException {
		this.idManagerMap.put(id, aWorkerManager);
		logger.info("registered worker manager : " + id);
		logger.info("size of map : " + idManagerMap.size());

		if (idManagerMap.size() == this.numMachines) {
			try {
				executeTask();
			} catch (IOException e) {
				logger.severe(e.toString());
				throw new RemoteException(e.toString());
			}
		}

	}

	public static void main(String args[]) throws IllegalClassException {
		String vertexClassName = args[0];
		int numMachines = Integer.parseInt(args[1]);
		try {
			Class<?> c = Class.forName(vertexClassName);
			if (!c.getSuperclass().equals(Vertex.class)) {
				throw new IllegalClassException(vertexClassName);
			}

		} catch (ClassNotFoundException e) {
			System.err.println("Client vertex class ["+vertexClassName+"] not found !");
			e.printStackTrace();
			return;
		}
		if (System.getSecurityManager() == null) {
			System.setSecurityManager(new SecurityManager());
		}
		try {

			MasterImpl master = new MasterImpl(vertexClassName, numMachines);
			Registry registry = LocateRegistry.createRegistry(PORT_NUMBER);
			registry.rebind(ManagerToMaster.SERVICE_NAME, master);
			System.out.println("Master instance bound");
		} catch (Exception e) {
			System.err.println("Can't bind Master instance");
			e.printStackTrace();

		}
	}

	public int getWorkerMgrsCount() {
		logger.info("returning : " + idManagerMap.size());
		return this.idManagerMap.size();
	}

	public int getWorkerMgrThreads() {
		return JPregelConstants.WORKER_MGR_THREADS;
	}

	public synchronized boolean isActive() {
		return this.isActive;
	}

	public synchronized void deactivate() {
		this.isActive = false;
	}

	public synchronized void activate() {
		this.isActive = true;
	}

	public void executeTask() throws RemoteException {
		try {
			initTime=System.nanoTime();
			this.gp = new GraphPartitioner(JPregelConstants.GRAPH_FILE, this,
					this.getVertexClassName());
		} catch (IOException e) {
			logger.severe(e.toString());
			throw new RemoteException(e.getMessage(),e);
		}
		
		this.setAllDone(true);
		this.activate();
		logger.info("Starting superstep executor thread");
		superstepExecutorThread.start();
		logger.info("Starting fault detector thread");
		faultDetectorThread.start();
	}

	public void run() {
		
		while (true) {
			if (this.isActive()) {
				try {
					while (!allDone()) {

					}
					synchronized (idManagerMap) {
						if (!isWkrMgrsInitialized()) {
							
							initializeWorkerManagers();
							setWkrMgrsInitialized(true);
						}

						if (findActiveManagers(this.getSuperStep()) > 0) {
							try {
								Thread.sleep(10);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
							
							List<Future<Void>> list = new ArrayList<Future<Void>>();
						    ExecutorService executor = Executors.newFixedThreadPool(this.getWorkerMgrsCount());
							for (Map.Entry<String, WorkerManager> e : this.idManagerMap
									.entrySet()) {
								String aWkrMgrId = e.getKey();
								final WorkerManager aWkrMgr = e.getValue();
								logger.info("Send messages : "
										+ this.getSuperStep()
										+ " from worker manager : " + aWkrMgrId);
								
								Callable<Void> callable=new Callable<Void>() {

									@Override
									public Void call() throws Exception {
										aWkrMgr.setupSuperStep(getSuperStep());
										return null;
									}
								};
								list.add(executor.submit(callable));
								
							}
							
							for (Future<Void> future : list)
								future.get();
							
							this.setAllDone(false);

							this.setParticipatingMgrs(this.idManagerMap.size());
							this.returnedManagers.clear();
							for (Map.Entry<String, WorkerManager> e : this.idManagerMap
									.entrySet()) {
								String aWkrMgrId = e.getKey();
								WorkerManager aWkrMgr = e.getValue();

								logger.info("Commencing superstep : "
										+ this.getSuperStep()
										+ " in worker manager : " + aWkrMgrId);
								aWkrMgr.beginSuperStep(this.getSuperStep(),
										this.isCheckPoint());
							}
							logger.info("Waiting for worker managers to complete execution");
							while (isActive() && !allDone()) {
								//System.out.println("+ isActive() && !allDone() + ");	
							}
							if (isActive()) {
								logger.info("Superstep over : "
										+ this.getSuperStep());
								this.setSuperStep(this.getSuperStep() + 1);
							}
						} else {
							logger.info("-----------------------------------------------------");
							logger.info("Writing Solutions");
							// Writing solutions
							writeSolutions();
							endTime= System.nanoTime();
							System.out.println("Task took "+ ((endTime - initTime)/1000000));
							System.err.println("Computations completed. Solutions written to solutions/. Logs in logs/ !");
							break;
						}

					}

				} catch (RemoteException e) {
					logger.severe(e.toString());
					e.printStackTrace();
					this.deactivate();
				} catch (IOException e) {
					logger.severe(e.getMessage());
					e.printStackTrace();
					break;
				} catch (IllegalInputException e) {
					logger.severe(e.getMessage());
					e.printStackTrace();
					break;
				} catch (DataNotFoundException e) {
					logger.severe(e.getMessage());
					e.printStackTrace();
					break;
				} catch (InstantiationException e) {
					logger.severe(e.getMessage());
					e.printStackTrace();
					break;
				} catch (IllegalAccessException e) {
					logger.severe(e.getMessage());
					e.printStackTrace();
					break;
				} catch (ClassNotFoundException e) {
					logger.severe(e.getMessage());
					e.printStackTrace();
					break;
				} catch (NoResourcesException e) {
					logger.severe(e.getMessage());
					e.printStackTrace();
					break;
				} catch (InterruptedException e1) {
					logger.severe(e1.getMessage());
					e1.printStackTrace();
				} catch (ExecutionException e1) {
					logger.severe(e1.getMessage());
					e1.printStackTrace();
				}

			}
		}

	}

	/**
	 * @param b
	 */
	private synchronized void setWkrMgrsInitialized(boolean newState) {
		this.isWkrMgrsInitialized = newState;
	}

	/**
	 * @return
	 */
	private synchronized boolean isWkrMgrsInitialized() {
		return this.isWkrMgrsInitialized;
	}

	/**
	 * @return
	 */
	private boolean isCheckPoint() {
		if (this.getSuperStep() % JPregelConstants.CHECKPOINT_INTERVAL == 0) {
			return true;
		}
		return false;
	}

	/**
	 * Concurrent storage of solutions in master
	 * 
	 * @throws RemoteException
	 * 
	 */
	private void writeSolutions() throws RemoteException {
		/*for (Map.Entry<String, WorkerManager> e : this.idManagerMap.entrySet()) {
			WorkerManager aWkrMgr = e.getValue();
			aWkrMgr.writeSolutions();
		}*/
		logger.info("Solutions storing");
		//Storage solutions in master
		List<Future<List<GraphPartition>>> list = new ArrayList<Future<List<GraphPartition>>>();
	    ExecutorService executor = Executors.newFixedThreadPool(this.getWorkerMgrsCount());
		for (Map.Entry<String, WorkerManager> e : this.idManagerMap.entrySet()) {
			final WorkerManager aWkrMgr = e.getValue();
			Callable<List<GraphPartition>> callable=new Callable<List<GraphPartition>>() {

				@Override
				public List<GraphPartition> call() throws Exception {
					return aWkrMgr.getSolutions();
				}
			};
			list.add(executor.submit(callable));

		}
		try {
			for(Future<List<GraphPartition>> future : list){
				for(GraphPartition graphPartition : future.get()){
					graphPartition.writeSolutions();
				}
			}
		logger.info("End of the solutions storage");
		} catch (InterruptedException e1) {
			logger.severe("Error storing solutions "+e1.getMessage());
			e1.printStackTrace();
		} catch (ExecutionException e1) {
			logger.severe("Error storing solutions "+e1.getMessage());
			e1.printStackTrace();
		} catch (IOException e1) {
			logger.severe("Error storing solutions "+e1.getMessage());
			e1.printStackTrace();
		}
		

	}

	/**
	 * @return
	 * @throws RemoteException
	 */
	private int findActiveManagers(int superStep) throws RemoteException {
		if (superStep == JPregelConstants.FIRST_SUPERSTEP) {
			return this.idManagerMap.size();
		}
		int activeManagers = 0;
		for (Map.Entry<String, WorkerManager> e : this.idManagerMap.entrySet()) {
			MessageSpooler aSpooler = (MessageSpooler) e.getValue();
			if (!aSpooler.isQueueEmpty()) {
				activeManagers++;
			}
		}
		return activeManagers;

	}

	/**
	 * @return
	 */
	private synchronized boolean allDone() {
		return allDone;
	}

	private void restoreState() throws IOException, IllegalInputException,
			DataNotFoundException, InstantiationException,
			IllegalAccessException, ClassNotFoundException,
			NoResourcesException {

		if (idManagerMap.size() == 0) {
			throw new NoResourcesException(
					"No worker managers available to the Master for queueing jobs");
		}

		List<List<Integer>> assignedPartitions = this.assignPartitions();
		Map<Integer, Pair<String, String>> partitionWkrMgrMap = this
				.getPartitionMap(assignedPartitions);
		this.restoreState(assignedPartitions);

		// Write partition - worker manager map to file
		DataLocator dl = DataLocator.getDataLocator(gp.getPartitionSize());
		dl.writePartitionMap(partitionWkrMgrMap);
		logger.info("Restored state");
	}

	private void restoreState(List<List<Integer>> assignedPartitions) {
		int index = 0;
		for (Map.Entry<String, WorkerManager> anEntry : idManagerMap.entrySet()) {
			String wkrMgrToBeRestored = anEntry.getKey();

			try {
				System.err.println(wkrMgrToBeRestored + " restoring state");
				this.logger.info(wkrMgrToBeRestored + " restoring state");
				anEntry.getValue().restoreState(getLastCheckPoint(),
						assignedPartitions.get(index));
				System.err.println(wkrMgrToBeRestored + " state restoration - SUCCESSFUL !");
			} catch (RemoteException e) {
				// can't really catch this now, ignore
				e.printStackTrace();
			}
			index++;
		}
	}

	private void stopSuperStep() {
		for (Map.Entry<String, WorkerManager> anEntry : idManagerMap.entrySet()) {
			String wkrMgrToBeStopped = anEntry.getKey();

			try {
				System.err.println(wkrMgrToBeStopped + " stopping superstep");
				this.logger.info(wkrMgrToBeStopped + " stopping superstep");
				anEntry.getValue().stopSuperStep();
			} catch (RemoteException e1) {
				// can't really catch this now, ignore
				e1.printStackTrace();
			}

		}
	}

	/**
	 * @throws IOException
	 * @throws DataNotFoundException
	 * @throws IllegalInputException
	 * @throws ClassNotFoundException
	 * @throws IllegalAccessException
	 * @throws InstantiationException
	 * @throws NoResourcesException
	 * 
	 */
	private void initializeWorkerManagers() throws IOException,
			IllegalInputException, DataNotFoundException,
			InstantiationException, IllegalAccessException,
			ClassNotFoundException, NoResourcesException {

		if (idManagerMap.size() == 0) {
			throw new NoResourcesException(
					"No worker managers available to the Master for queueing jobs");
		}

		int numPartitions = this.gp.partitionGraphs();
		logger.info("Num partitions : " + numPartitions);
		List<List<Integer>> assignedPartitions = this.assignPartitions();
		Map<Integer, Pair<String, String>> partitionWkrMgrMap = this.getPartitionMap(assignedPartitions);
		this.initializeWorkerManagers(assignedPartitions, partitionWkrMgrMap);
		// Write partition - worker manager map to file
		DataLocator dl = DataLocator.getDataLocator(gp.getPartitionSize());
		//XXX: partitions only storage in worker manager
		//dl.writePartitionMap(partitionWkrMgrMap);
		logger.info("Initialized worker managers : ");
		dl.clearSolutions();
		logger.info("Cleared solutions folder");
	}

	private Map<Integer, Pair<String, String>> getPartitionMap(
			List<List<Integer>> assignedPartitions) throws RemoteException {
		Map<Integer, Pair<String, String>> partitionWkrMgrMap = new HashMap<Integer, Pair<String, String>>();
		int index = 0;
		WorkerManager thisWkrMgr = null;
		String thisWkrMgrId = null;
		for (Map.Entry<String, WorkerManager> e : this.idManagerMap.entrySet()) {
			thisWkrMgrId = e.getKey();
			thisWkrMgr = e.getValue();
			/*List<Integer> thisWkrMgrPartitions = assignedPartitions.get(index);
			for (int partition : thisWkrMgrPartitions) {
				partitionWkrMgrMap.put(partition, new Pair<String, String>(
						thisWkrMgrId, thisWkrMgr.getHostInfo()));
			}*/
			partitionWkrMgrMap.put(index, new Pair<String, String>(thisWkrMgrId, thisWkrMgr.getHostInfo()));
			index++;
		}
		return partitionWkrMgrMap;
	}

	private void initializeWorkerManagers(List<List<Integer>> assignedPartitions,Map<Integer, Pair<String, String>> partitionWkrMgrMap)
			throws RemoteException {
		int index = 0;
		WorkerManager thisWkrMgr = null;
		for (Map.Entry<String, WorkerManager> e : this.idManagerMap.entrySet()) {
			thisWkrMgr = e.getValue();
			List<Integer> thisWkrMgrPartitions = assignedPartitions.get(index);
			//Get In Memory Representation of Partition
			List<GraphPartition> graphParitions= new Vector<GraphPartition>();
			for (int partitionID : thisWkrMgrPartitions){
				graphParitions.add(this.gp.getPartition(partitionID));
			}
			
			logger.info("Initialize Worker Manager " + thisWkrMgr.getId());
			thisWkrMgr.initialize(graphParitions, this.getWorkerMgrThreads(), 
					this.gp.getPartitionSize(), this.gp.getNumVertices(), partitionWkrMgrMap, (int)(this.gp.getNumberOfPartitions() / this.getWorkerMgrsCount()));
			
			// Free partitions cache
			for (GraphPartition part : graphParitions)
				part.freePartition();
			logger.info("End initialize Worker Manager " + thisWkrMgr.getId());
			index++;
		}
	}

	private List<List<Integer>> assignPartitions() throws NoResourcesException,
			IOException, IllegalInputException, DataNotFoundException,
			InstantiationException, IllegalAccessException,
			ClassNotFoundException {
		
		if (idManagerMap.size() == 0) {
			throw new NoResourcesException("No worker managers available to the Master for queueing jobs");
		}
		logger.info(idManagerMap.toString());

		List<List<Integer>> assignedPartitions = new Vector<List<Integer>>();

		int numMgrPartitions = this.gp.getNumberOfPartitions() / this.getWorkerMgrsCount();
		List<Integer> wkrMgrPartitions = new Vector<Integer>();
		int partitionCount = 0;
		WorkerManager thisWkrMgr = null;
		for (Map.Entry<String, WorkerManager> e : this.idManagerMap.entrySet()) {
			if (thisWkrMgr != null) {
				logger.info("Generating partitions for worker manager : "
						+ thisWkrMgr.getId() + " -> " + wkrMgrPartitions);
				assignedPartitions.add(wkrMgrPartitions);
			}
			wkrMgrPartitions = new Vector<Integer>();
			thisWkrMgr = e.getValue();
			for (int i = 0; i < numMgrPartitions; i++, partitionCount++) {
				wkrMgrPartitions.add(partitionCount);
				logger.info("added : " + partitionCount);
			}
			logger.info("End of loop : " + wkrMgrPartitions);
		}
		
		//Assign the remaining Partitions to last WorkerManager 
		while (partitionCount < this.gp.getNumberOfPartitions()) {
			wkrMgrPartitions.add(partitionCount);
			partitionCount++;
		}

		assignedPartitions.add(wkrMgrPartitions);

		logger.info("Assigned partitions : " + assignedPartitions);
		return assignedPartitions;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.ManagerToMaster#endSuperStep(java.lang.String)
	 */
	@Override
	public void endSuperStep(String wkrMgrId) throws RemoteException {

		// this fellow shouldn't have returned before .. checking just in
		// case there is a double endSuperStep() done by a worker manager
		// during a stopSuperStep() call
		if (!this.returnedManagers.contains(wkrMgrId)
				&& superStep == this.getSuperStep()) {
			// Checking if any managers are yet to report completion
			if (this.getParticipatingMgrs() > 0) {

				logger.info("Worker manager : " + wkrMgrId
						+ " has reported completion of superstep : "
						+ this.getSuperStep());
				this.setParticipatingMgrs(this.getParticipatingMgrs() - 1);
				this.returnedManagers.add(wkrMgrId);

				if (this.getParticipatingMgrs() == 0) {
					logger.info("All worker managers reported completion of superstep : "
							+ this.getSuperStep());
					if (this.isCheckPoint() && this.isActive()) {
						logger.info("#############################");
						logger.info("Checkpointed data at superstep : "
								+ this.getSuperStep());
						logger.info("#############################");
						this.setLastCheckPoint(this.getSuperStep());

					}
					setAllDone(true);
				}
			}
		}

	}

	/**
	 * @param allDone
	 */
	private synchronized void setAllDone(boolean allDone) {
		this.allDone = allDone;

	}

}
