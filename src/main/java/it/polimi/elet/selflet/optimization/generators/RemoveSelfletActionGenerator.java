package it.polimi.elet.selflet.optimization.generators;

import static it.polimi.elet.selflet.negotiation.nodeState.NodeStateGenericDataEnum.CPU_UTILIZATION;

import java.util.Collection;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.inject.Inject;

import it.polimi.elet.selflet.configuration.SelfletConfiguration;
import it.polimi.elet.selflet.exceptions.NotFoundException;
import it.polimi.elet.selflet.knowledge.IServiceKnowledge;
import it.polimi.elet.selflet.knowledge.Neighbor;
import it.polimi.elet.selflet.negotiation.nodeState.INeighborStateManager;
import it.polimi.elet.selflet.negotiation.nodeState.INodeState;
import it.polimi.elet.selflet.optimization.actions.IOptimizationAction;
import it.polimi.elet.selflet.optimization.actions.scaling.RemoveSelfletAction;
import it.polimi.elet.selflet.service.Service;
import it.polimi.elet.selflet.service.utilization.IPerformanceMonitor;

/**
 * Generators for actions removing the current selflet
 * 
 * @author Nicola Calcavecchia <calcavecchia@gmail.com>
 * */
public class RemoveSelfletActionGenerator implements IActionGenerator {

	private static final long MINIMUM_TIME_TO_REMOVE_SELFLET = SelfletConfiguration
			.getSingleton().minimumTimeToRemoveSelfletInSec * 1000;
	private static final long MINIMUM_TIME_BETWEEN_TWO_REMOVAL_ACTIONS = SelfletConfiguration
			.getSingleton().minimumTimeBetweenTwoRemovalActionsInSec * 1000;
	private static final long BILL_TIME = SelfletConfiguration.getSingleton().billTime * 1000;

	private final INeighborStateManager neighborStateManager;
	private final IPerformanceMonitor performanceMonitor;
	private final IServiceKnowledge serviceKnowledge;
	private final long startupTime;
	private long lastTimeCreatedARemoval;

	@Inject
	public RemoveSelfletActionGenerator(
			INeighborStateManager neighborStateManager,
			IPerformanceMonitor performanceMonitor,
			IServiceKnowledge serviceKnowledge) {
		this.neighborStateManager = neighborStateManager;
		this.performanceMonitor = performanceMonitor;
		this.serviceKnowledge = serviceKnowledge;
		this.startupTime = System.currentTimeMillis();
		this.lastTimeCreatedARemoval = System.currentTimeMillis();
	}

	@Override
	public Collection<? extends IOptimizationAction> generateActions() {

		// || stillReceivingRequests()
		if (isInPayedTime() || selfletIsLoaded() || theOnlySelflet()
				|| removalActionRecentlyCreated()
				|| theOnlyOneProvidingSomeService() || neighborsAreLoaded()) {
			return Lists.newArrayList();
		}

		IOptimizationAction removalAction = createRemovalAction();
		return Lists.newArrayList(removalAction);
	}

	private IOptimizationAction createRemovalAction() {
		this.lastTimeCreatedARemoval = System.currentTimeMillis();
		double weight = computeWeight();
		return new RemoveSelfletAction(weight);
	}

	private double computeWeight() {
		double lowerBound = performanceMonitor.getCPUUtilizationLowerBound();
		double currentUtilization = performanceMonitor
				.getCurrentTotalCPUUtilization();
		//TODO define the best policy to compute weight
		return lowerBound - currentUtilization;
	}

	private boolean removalActionRecentlyCreated() {
		long now = System.currentTimeMillis();
		long elapsed = now - lastTimeCreatedARemoval;
		return (elapsed < MINIMUM_TIME_BETWEEN_TWO_REMOVAL_ACTIONS);
	}

	private boolean stillReceivingRequests() {
		for (Service service : serviceKnowledge.getServices()) {
			double requestRate = performanceMonitor
					.getServiceRequestRate(service.getName());
			if (requestRate > 0) {
				return true;
			}
		}
		return false;
	}

	private boolean selfletIsLoaded() {
		double currentUtilization = performanceMonitor
				.getCurrentTotalCPUUtilization();
		double lowerBound = performanceMonitor.getCPUUtilizationLowerBound();
		return (currentUtilization >= lowerBound);
	}

	private boolean theOnlySelflet() {
		return neighborStateManager.getNeighbors().isEmpty();
	}

	private boolean theOnlyOneProvidingSomeService() {
		boolean theOnlyProvider = false;

		for (Service service : serviceKnowledge.getServices()) {
			try {
				neighborStateManager
						.getNeighborHavingService(service.getName());
			} catch (NotFoundException e) {
				theOnlyProvider = true;
			}
		}

		return theOnlyProvider;
	}

	// TODO replaced by the method below, because takes into consideration the
	// payed time. If it works, delete this function.
	private boolean selfletRecentlyCreated() {
		long now = System.currentTimeMillis();
		long elapsed = now - startupTime;
		return elapsed < MINIMUM_TIME_TO_REMOVE_SELFLET;
	}

	private boolean isInPayedTime() {
		if (BILL_TIME > MINIMUM_TIME_BETWEEN_TWO_REMOVAL_ACTIONS) {
			long now = System.currentTimeMillis();
			long mod = (now - startupTime) % BILL_TIME;
			return mod < MINIMUM_TIME_TO_REMOVE_SELFLET;
		}
		
		return selfletRecentlyCreated();

	}

	private boolean neighborsAreLoaded() {

		Set<Neighbor> neighbors = neighborStateManager.getNeighbors();

		if (neighbors.isEmpty()) {
			return false;
		}

		return (computeUtilizationAverage() >= performanceMonitor
				.getCPUUtilizationUpperBound());
	}

	private double computeUtilizationAverage() {
		Set<Neighbor> neighbors = neighborStateManager.getNeighbors();
		double utilizationSum = 0;
		int count = 0;

		for (Neighbor neighbor : neighbors) {
			if (neighborStateManager.haveInformationAboutNeighbor(neighbor)) {
				INodeState nodeState = neighborStateManager
						.getNodeStateOfNeighbor(neighbor);
				double neighborUtilization = (Double) nodeState
						.getGenericDataWithKey(CPU_UTILIZATION.toString());
				utilizationSum += neighborUtilization;
				count++;
			}
		}

		if (count == 0) {
			return 0;
		}

		double utilizationAverage = utilizationSum / count;
		return utilizationAverage;
	}

}
