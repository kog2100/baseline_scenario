package ch.ethz.matsim.baseline_scenario.preparation;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup.ModeRoutingParams;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.router.DijkstraFactory;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.MainModeIdentifierImpl;
import org.matsim.core.router.NetworkRoutingModule;
import org.matsim.core.router.PlanRouter;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.TeleportationRoutingModule;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import com.google.inject.Provider;

public class Routing {
	static public void main(String[] args) throws SecurityException, NoSuchMethodException, IllegalAccessException,
			IllegalArgumentException, InvocationTargetException, InterruptedException {
		String configPath = args[0];
		String outputPath = args[1];

		Config config = ConfigUtils.loadConfig(configPath);
		Scenario scenario = ScenarioUtils.loadScenario(config);

		Network carNetwork = NetworkUtils.createNetwork();
		new TransportModeNetworkFilter(scenario.getNetwork()).filter(carNetwork, Collections.singleton("car"));

		Iterator<? extends Person> personIterator = scenario.getPopulation().getPersons().values().iterator();

		int numberOfThreads = config.global().getNumberOfThreads();
		List<Thread> threads = new LinkedList<>();

		for (int i = 0; i < numberOfThreads; i++) {
			threads.add(new Thread(new RoutingRunner(config, carNetwork, personIterator)));
		}

		for (Thread thread : threads) {
			thread.start();
		}

		for (Thread thread : threads) {
			thread.join();
		}

		new PopulationWriter(scenario.getPopulation()).write(outputPath);
	}

	static public class RoutingRunner implements Runnable {
		private final Config config;
		private final Network carNetwork;
		private final Iterator<? extends Person> personIterator;

		public RoutingRunner(Config config, Network carNetwork, Iterator<? extends Person> personIterator) {
			this.config = config;
			this.carNetwork = carNetwork;
			this.personIterator = personIterator;
		}

		@Override
		public void run() {
			try {
				PlanRouter planRouter = new PlanRouter(createRouter(config, carNetwork));

				while (true) {
					Person person = null;

					synchronized (personIterator) {
						if (personIterator.hasNext()) {
							person = personIterator.next();
						} else {
							return;
						}
					}

					planRouter.run(person);
				}

			} catch (SecurityException | NoSuchMethodException | IllegalAccessException | IllegalArgumentException
					| InvocationTargetException e) {
				throw new RuntimeException(e);
			}
		}
	}

	static public TripRouter createRouter(Config config, Network carNetwork) throws SecurityException,
			NoSuchMethodException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		MainModeIdentifier mainModeIdentifier = new MainModeIdentifierImpl();
		PopulationFactory populationFactory = PopulationUtils.getFactory();

		TripRouter.Builder tripRouterBuilder = new TripRouter.Builder(config);
		tripRouterBuilder.setMainModeIdentifier(mainModeIdentifier);

		TravelTime travelTime = new FreeSpeedTravelTime();
		TravelDisutility travelDisutility = new OnlyTimeDependentTravelDisutility(travelTime);

		LeastCostPathCalculator carRouter = new DijkstraFactory().createPathCalculator(carNetwork, travelDisutility,
				travelTime);
		NetworkRoutingModule carRoutingModule = new NetworkRoutingModule("car", populationFactory, carNetwork,
				carRouter);
		tripRouterBuilder.putRoutingModule("car", new RoutingModuleProvider(carRoutingModule));

		ModeRoutingParams walkParams = config.plansCalcRoute().getModeRoutingParams().get("walk");
		TeleportationRoutingModule walkRoutingModule = new TeleportationRoutingModule("walk", populationFactory,
				walkParams.getTeleportedModeSpeed(), walkParams.getBeelineDistanceFactor());
		tripRouterBuilder.putRoutingModule("walk", new RoutingModuleProvider(walkRoutingModule));

		ModeRoutingParams bikeParams = config.plansCalcRoute().getModeRoutingParams().get("bike");
		TeleportationRoutingModule bikeRoutingModule = new TeleportationRoutingModule("bike", populationFactory,
				bikeParams.getTeleportedModeSpeed(), bikeParams.getBeelineDistanceFactor());
		tripRouterBuilder.putRoutingModule("bike", new RoutingModuleProvider(bikeRoutingModule));

		ModeRoutingParams ptParams = config.plansCalcRoute().getModeRoutingParams().get("pt");
		TeleportationRoutingModule ptRoutingModule = new TeleportationRoutingModule("pt", populationFactory,
				ptParams.getTeleportedModeSpeed(), ptParams.getBeelineDistanceFactor());
		tripRouterBuilder.putRoutingModule("pt", new RoutingModuleProvider(ptRoutingModule));

		TripRouter.class.getMethod("build").setAccessible(true);
		return (TripRouter) TripRouter.class.getMethod("build").invoke(tripRouterBuilder);
	}

	static public class RoutingModuleProvider implements Provider<RoutingModule> {
		private final RoutingModule routingModule;

		public RoutingModuleProvider(RoutingModule routingModule) {
			this.routingModule = routingModule;
		}

		@Override
		public RoutingModule get() {
			return routingModule;
		}
	}
}