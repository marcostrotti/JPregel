/**
 * 
 */
package system;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Vector;
import java.util.logging.Logger;

import system.Communicator.CommunicatorState;
import system.Worker.WorkerState;
import utility.JPregelLogger;
import utility.Pair;
import api.Vertex;
import exceptions.DataNotFoundException;
import exceptions.IllegalClassException;
import exceptions.IllegalInputException;
import exceptions.IllegalMessageException;
import graphs.GraphPartition;

/**
 * 
 * Implementation of interface worker manager.
 * 
 * @author Manasa Chandrasekhar
 * @author Kowshik Prakasam
 * 
 */
public class WorkerManagerImpl extends UnicastRemoteObject implements
		WorkerManager, MessageSpooler {

	/**
	 * 
	 */
	private static final long serialVersionUID = -2375569067650804665L;
	private static int PORT_NUMBER;
	static {
		PORT_NUMBER = new Random().nextInt(3000) + 2000;
	}

	private String id;
	private Logger logger;
	private static final String LOG_FILE_PREFIX = JPregelConstants.LOG_DIR
			+ "workermanager_";
	private static final String LOG_FILE_SUFFIX = ".log";
	private ManagerToMaster master;

	private Map<Integer, Vertex> idVertexMap;
	private List<Worker> workers;
	private String vertexClassName;
	private List<Message> incomingMsgs;
	private Communicator aCommunicator;
	private int superStep;
	private boolean isCheckPoint;
	private int numWorkers;
	private boolean justRecovered;
	
	private Vector<String> runningWorkers;

	public Communicator getCommunicator() {
		return aCommunicator;
	}

	public void setaCommunicator(Communicator aCommunicator) {
		this.aCommunicator = aCommunicator;
	}

	private void initLogger() throws IOException {
		this.logger = JPregelLogger.getLogger(this.getId(), LOG_FILE_PREFIX
				+ this.getId() + LOG_FILE_SUFFIX);
	}

	private WorkerManagerImpl() throws IOException {
		this.workers = new Vector<Worker>();

		this.idVertexMap = new HashMap<Integer, Vertex>();
		this.incomingMsgs = new LinkedList<Message>();
		this.setId(InetAddress.getLocalHost().getHostName() + "_"
				+ WorkerManagerImpl.getRandomChars());
		this.aCommunicator = new Communicator(this, this.getId());
		this.numWorkers = 1;
		this.initLogger();
	}

	public WorkerManagerImpl(ManagerToMaster master, String vertexClassName)
			throws IOException {
		this();
		this.master = master;
		this.vertexClassName = vertexClassName;
	}

	/**
	 * @param string
	 */
	private void setId(String id) {
		this.id = id;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.WorkerManager#getId()
	 */
	@Override
	public String getId() {
		return id;
	}

	public static void main(String[] args) throws IOException,
			IllegalClassException {
		String masterServer = args[0];
		String vertexClassName = args[1];
		try {
			Class<?> c = Class.forName(vertexClassName);
			if (!c.getSuperclass().equals(Vertex.class)) {
				throw new IllegalClassException(vertexClassName);
			}

		} catch (ClassNotFoundException e) {
			System.err.println("Client vertex class not found !");
			e.printStackTrace();
		}

		if (System.getSecurityManager() == null) {
			System.setSecurityManager(new SecurityManager());
		}
		try {
			System.out.println("Looking up ManagerToMaster service : "
					+ masterServer + "/" + ManagerToMaster.SERVICE_NAME);
			ManagerToMaster master = (ManagerToMaster) Naming.lookup("//"
					+ masterServer + "/" + ManagerToMaster.SERVICE_NAME);
			WorkerManagerImpl mgr = new WorkerManagerImpl(master,
					vertexClassName);
			Registry registry = LocateRegistry.createRegistry(PORT_NUMBER);
			registry.rebind(mgr.getId(), mgr);
			System.out.println("Worker manager registered as " + mgr.getId()
					+ " in port : " + PORT_NUMBER);
			master.register(mgr, mgr.getId());
			System.out.println("Worker Manager registered to master");
		} catch (RemoteException e) {
			System.err.println("WorkerManagerImpl Remote exception : ");
			e.printStackTrace();

		} catch (MalformedURLException e) {
			System.err.println("WorkerManagerImpl Malformed exception : ");
			e.printStackTrace();
		} catch (NotBoundException e) {
			System.err.println("WorkerManagerImpl NotBound exception : ");
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * @return A random name made up of exactly three alphabets
	 */
	public static String getRandomChars() {
		char first = (char) ((new Random().nextInt(26)) + 65);
		char second = (char) ((new Random().nextInt(26)) + 65);
		char third = (char) ((new Random().nextInt(26)) + 65);
		return "" + first + second + third;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.WorkerManager#initialize(java.util.List)
	 */
	@Override
	public void initialize(List<GraphPartition> partitions, int numWorkers,
			int partitionSize, int numVertices,Map<Integer, Pair<String, String>> partitionMap){
		//XXX: 
		this.runningWorkers=new Vector<String>();
		logger.info("Received partitionNumbers : " + partitions);
		this.setNumWorkers(numWorkers);
		// Set datalocator in communicator
		DataLocator aDataLocator = null;
		try {
			//
			aDataLocator = DataLocator.getDataLocator(partitionSize);
			aDataLocator.writePartitionMap(partitionMap);
			this.getCommunicator().setDataLocator(aDataLocator);
		} catch (IOException e) {
			String msg = "RemoteException in DataLocator in worker manager : "
					+ this.getId();
			logger.severe(msg);
			e.printStackTrace();
		} catch (DataNotFoundException e) {
			logger.severe("RemoteException in DataLocator in worker manager : "	+ this.getId());
			e.printStackTrace();
		}
		
		// Locally storage assigned partitions
		List<Integer> partitionNumbers=new Vector<Integer>();
		for(GraphPartition partition : partitions){
			try {
				partition.setDataLocator(aDataLocator);
				partition.writeToFile(aDataLocator.getPartitionFile(partition.getPartitionID()));
			} catch (IOException e) {
				logger.severe("Error writing partition "+ partition.getPartitionID() +" to file "+ e.getMessage());
				e.printStackTrace();
			} catch (DataNotFoundException e) {
				logger.severe("Error writing partition "+ partition.getPartitionID() +" to file "+ e.getMessage());
				e.printStackTrace();
			}
			partitionNumbers.add(partition.getPartitionID());
		}
		
		// assign partitions to workers
		List<List<Integer>> assignedPartitions = this
				.assignPartitions(partitionNumbers);
		// for each assigned partition, initialize the worker
		for (List<Integer> threadPartition : assignedPartitions) {
			try {
				Worker aWkr = new Worker(threadPartition, partitionSize, this,
						vertexClassName, this.getCommunicator(), numVertices);
				this.getCommunicator().registerWorker(aWkr);
				this.idVertexMap.putAll(aWkr.getVertices());
				logger.info("Cached all vertices of this worker. Size of id->vertex map is : "
						+ this.idVertexMap.size());
				this.workers.add(aWkr);
				logger.info("Added new worker : " + aWkr);
			} catch (DataNotFoundException e) {
				logger.severe("Unable to read partitions in worker manager : "
						+ this.getId());
				e.printStackTrace();
			} catch (IOException e) {
				logger.severe("Unable to read partitions in worker manager : "
						+ this.getId());
				e.printStackTrace();
			} catch (IllegalInputException e) {
				logger.severe("Unable to read partitions in worker manager : "
						+ this.getId());
				e.printStackTrace();
			} catch (InstantiationException e) {
				logger.severe("Unable to instantiate client vertex class : "
						+ vertexClassName);
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				logger.severe("Unable to instantiate client vertex class : "
						+ vertexClassName);
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				logger.severe("Client vertex class not found : "
						+ vertexClassName);
				e.printStackTrace();
			}
		}
		System.out.println("Initialized worker manager : " + this.getId()
		+ "\n\n Workers are : " + workers);
		logger.info("Initialized worker manager : " + this.getId()
				+ "\n\n Workers are : " + workers);
	}

	/**
	 * @param b
	 */
	private synchronized void setRecoveryStep(boolean isRecoveryStep) {
		this.justRecovered = isRecoveryStep;
	}

	private synchronized boolean justRecovered() {
		return this.justRecovered;
	}

	// Returns partition assignments to worker managers
	private List<List<Integer>> assignPartitions(List<Integer> partitions) {
		Iterator<Integer> it = partitions.iterator();
		List<Integer> threadPartitions = new Vector<Integer>();
		List<List<Integer>> assignedPartitions = new Vector<List<Integer>>();
		int wkrPartitionCount = partitions.size() / this.getNumWorkers();
		if (wkrPartitionCount == 0 && this.getNumWorkers() != 0) {
			wkrPartitionCount = partitions.size();
		}

		int assignedWrkrs = 0;
		while (it.hasNext() && (assignedWrkrs != this.getNumWorkers())) {
			int thisWkrPartitionCount = 0;
			threadPartitions = new Vector<Integer>();
			while (thisWkrPartitionCount < wkrPartitionCount && it.hasNext()) {
				threadPartitions.add(it.next());
				thisWkrPartitionCount++;
			}
			if (thisWkrPartitionCount > 0) {

				if (it.hasNext() && assignedWrkrs + 1 == this.getNumWorkers()) {
					while (it.hasNext()) {
						threadPartitions.add(it.next());
					}
				}
				assignedPartitions.add(threadPartitions);
				assignedWrkrs++;
			}
		}

		return assignedPartitions;
	}

	/**
	 * @param numWorkers
	 */
	private void setNumWorkers(int numWorkers) {
		this.numWorkers = numWorkers;

	}

	private int getNumWorkers() {
		return this.numWorkers;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.WorkerManager#executeSuperStep()
	 */
	@Override
	public void beginSuperStep(int superStepNumber, boolean isCheckPoint)
			throws RemoteException {
		this.superStep = superStepNumber;
		this.isCheckPoint = isCheckPoint;

		logger.info("Beginning superstep : " + superStepNumber);
		System.out.println("Beginning superstep : " + superStepNumber);
		// Distribute messages from last superstep
		try {
			boolean msgDistributed = false;
			if (!this.justRecovered()) {
				msgDistributed = distributeMessages();
			}
			this.setRecoveryStep(false);
			if (this.isCheckPoint
					|| superStep == JPregelConstants.FIRST_SUPERSTEP) {
				checkpointData();
			}
			
			if (msgDistributed
					|| (superStepNumber == JPregelConstants.FIRST_SUPERSTEP)) {

				// start communicator
				logger.info("Starting communicator");
				System.out.println("Starting communicator");
				aCommunicator.setState(Communicator.CommunicatorState.EXECUTE);
				logger.info("Set communicator state to EXECUTE");
				System.out.println("End Starting communicator");
				System.out.println("Init workers ");
				// start workers
				//Every time should be empty
				this.runningWorkers.clear();
				for (int index = 0; index < this.workers.size(); index++) {
					System.out.println("***** Starting worker "+ (index + 1 ) + " of " + this.workers.size());
					Worker aWorker = this.workers.get(index);
					this.runningWorkers.add(aWorker.getId());
					aWorker.setSuperStep(superStepNumber);
					aWorker.setState(Worker.WorkerState.EXECUTE);
					// Wakeup the Worker
					System.out.println("***** End Starting worker "+ (index + 1) + " of " + this.workers.size());
				}
				System.out.println("End of Init workers ");

			} else {
				logger.info("No messages in superstep : " + superStepNumber);
				System.out.println("No messages in superstep : " + superStepNumber);
				endSuperStep();
			}
		} catch (IllegalMessageException e) {
			logger.severe(e.getMessage());
			e.printStackTrace();
			throw new RemoteException(e.getMessage(), e);
		}

	}

	/**
	 * 
	 * @param workerID
	 */
	public synchronized void  endJob(String workerID){
		if (this.runningWorkers.contains(workerID))
			this.runningWorkers.remove(workerID);
		if (this.runningWorkers.isEmpty())
			try {
				System.out.println("Sending vertex messages");	
				this.aCommunicator.sendVertexMessages();
				System.out.println("End Sending vertex messages");
				System.out.println("Ending computation");
				endSuperStep();
				System.out.println("End superstep");
			} catch (UnknownHostException e) {
				logger.severe("UnknownHostException occured in communicate()");
				System.out.println("UnknownHostException occured in communicate()");
				e.printStackTrace();
			} catch (RemoteException e) {
				logger.severe("RemoteException occured in communicate()");
				System.out.println("RemoteException occured in communicate()");
				logger.severe(e.toString());
				e.printStackTrace();
			} catch (DataNotFoundException e) {
				logger.severe("DataNotFoundException occured in communicate()");
				System.out.println("DataNotFoundException occured in communicate()");
				e.printStackTrace();
			} catch (IOException e) {
				logger.severe("IOException occured in communicate() "+ e.getMessage());
				System.out.println("IOException occured in communicate() "+ e.getMessage());
				e.printStackTrace();
			} catch (NotBoundException e) {
				logger.severe("NotBoundException occured in communicate()");
				System.out.println("NotBoundException occured in communicate()");
				e.printStackTrace();
			}
	}
	/**
	 * @throws IllegalMessageException
	 * 
	 */
	private boolean distributeMessages() throws IllegalMessageException {
		if (!incomingMsgs.isEmpty()) {
			for (int index = 0; index < this.incomingMsgs.size(); index++) {
				Message msg = this.incomingMsgs.get(index);
				Vertex targetVertex = idVertexMap.get(msg.getDestVertexID());
				if (targetVertex == null) {
					throw new IllegalMessageException(msg, this.getId());
				}

				targetVertex.queueMessage(msg);
			}
			this.incomingMsgs.clear();
			return true;
		}

		return false;

	}

	public void endSuperStep() throws RemoteException {
		logger.info("Ending superstep");
		System.out.println("Ending superstep " + this.getId());
		master.endSuperStep(this.getId());
	}

	private void checkpointData() throws RemoteException {

		try {
			this.saveState();
		} catch (IOException e) {
			String msg = e.getMessage();
			logger.severe(msg);
			e.printStackTrace();
			throw new RemoteException(msg, e);
		} catch (DataNotFoundException e) {
			String msg = e.getMessage();
			logger.severe(msg);
			e.printStackTrace();
			throw new RemoteException(msg, e);
		}

	}

	/**
	 * @throws DataNotFoundException
	 * @throws IOException
	 * 
	 */
	private void saveState() throws IOException, DataNotFoundException {
		for (int index = 0; index < this.workers.size(); index++) {
			Worker aWorker = this.workers.get(index);
			aWorker.setSuperStep(this.superStep);
			aWorker.saveState();
			logger.info("Checkpointed data for worker : " + aWorker.getId());
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.VertexMessager#queueMessage(system.Message)
	 */
	@Override
	public synchronized void queueMessage(Message msg) throws RemoteException {
		logger.info("In superstep : " + this.superStep
				+ ", queued next superstep message : " + msg);
		this.incomingMsgs.add(msg);

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.VertexMessaging#getQueueSize()
	 */
	@Override
	public synchronized boolean isQueueEmpty() throws RemoteException {
		if (this.incomingMsgs.size() > 0 || this.justRecovered()) {
			return false;
		}
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.WorkerManager#getHostName()
	 */
	@Override
	public String getHostInfo() throws RemoteException {
		try {
			return InetAddress.getLocalHost().getHostName() + ":" + PORT_NUMBER;
		} catch (UnknownHostException e) {
			String msg = e.getMessage();
			logger.severe(msg);
			e.printStackTrace();
			throw new RemoteException(msg, e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.WorkerManager#writeSolutions()
	 */
	@Override
	public void writeSolutions() throws RemoteException {
		for (int index = 0; index < this.workers.size(); index++) {
			Worker aWorker = this.workers.get(index);
			try {
				aWorker.writeSolutions();
			} catch (IOException e) {
				String msg = e.getMessage();
				logger.severe(msg);
				e.printStackTrace();
				throw new RemoteException(msg, e);
			}
		}

	}

	
	/*
	 * (non-Javadoc)
	 * @see system.WorkerManager#getSolutions()
	 */
	@Override
	public List<GraphPartition> getSolutions() throws RemoteException {
		List<GraphPartition> graphPartitions= new Vector<GraphPartition>();
		for (Worker worker : this.workers){
			graphPartitions.addAll(worker.getWorkerPartitions());
		}
		return graphPartitions;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.WorkerManager#isAlive()
	 */
	@Override
	public void isAlive() throws RemoteException {
		// does nothing, dummy method to check if host is alive

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.WorkerManager#stopSuperStep()
	 */
	@Override
	public void stopSuperStep() throws RemoteException {
		logger.severe("Received STOP signal from Master");
		for (int index = 0; index < this.workers.size(); index++) {
			Worker aWorker = this.workers.get(index);
			aWorker.setState(WorkerState.STOP);
			logger.severe("Issued STOP signal to worker : " + aWorker.getId());
		}
		logger.severe("Issued STOP signal to communicator");
		aCommunicator.setState(CommunicatorState.STOP);
		this.endSuperStep();

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see system.WorkerManager#restoreState(int)
	 */
	@Override
	public void restoreState(int lastCheckPoint, List<Integer> partitions)
			throws RemoteException {
		logger.warning("Restoring to last check point number : "
				+ lastCheckPoint);
		logger.warning("Received new partitions : " + partitions.toString());

		// assign partitions to workers
		List<List<Integer>> assignedPartitions = this
				.assignPartitions(partitions);

		// clear global maps and queues
		this.incomingMsgs.clear();
		this.idVertexMap.clear();

		for (int index = 0; index < this.workers.size(); index++) {
			Worker aWorker = this.workers.get(index);
			try {
				logger.info("Restoring state of worker : " + aWorker.getId());
				aWorker.restoreState(lastCheckPoint, partitions);

				this.idVertexMap.putAll(aWorker.getVertices());

				logger.info("Restored all vertices for this worker. Size of id->vertex map now is : "
						+ this.idVertexMap.size());
			} catch (IOException e) {
				String msg = e.getMessage();
				logger.severe(msg);
				e.printStackTrace();
				throw new RemoteException(msg, e);
			} catch (DataNotFoundException e) {
				String msg = e.getMessage();
				logger.severe(msg);
				e.printStackTrace();
				throw new RemoteException(msg, e);
			} catch (ClassNotFoundException e) {
				String msg = e.getMessage();
				logger.severe(msg);
				e.printStackTrace();
				throw new RemoteException(msg, e);
			}
		}

	}

}
