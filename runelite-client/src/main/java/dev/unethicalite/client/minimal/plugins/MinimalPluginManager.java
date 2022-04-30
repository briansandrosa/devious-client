package dev.unethicalite.client.minimal.plugins;

import com.google.inject.Key;
import dev.unethicalite.api.input.Keyboard;
import dev.unethicalite.api.plugins.Plugins;
import dev.unethicalite.api.plugins.Script;
import dev.unethicalite.client.minimal.MinimalClient;
import dev.unethicalite.client.minimal.config.MinimalConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.jar.JarFile;

@Singleton
@Slf4j
public class MinimalPluginManager
{
	private static final File PLUGINS_DIR = new File(MinimalClient.CLIENT_DIR, "plugins");

	@Inject
	private MinimalConfig minimalConfig;

	@Inject
	private ExecutorService executorService;

	@Inject
	private Client client;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private WorldService worldService;

	private String[] args = null;
	private PluginEntry pluginEntry = null;
	@Getter
	private Plugin plugin = null;
	@Getter
	private Config config = null;

	private long randomDelay = 0;
	private boolean worldSet;

	public List<PluginEntry> loadPlugins()
	{
		return loadPlugins(PLUGINS_DIR);
	}

	public List<PluginEntry> loadPlugins(File dir)
	{
		List<PluginEntry> plugins = new ArrayList<>();

		try
		{
			File[] files = dir.listFiles();
			if (files == null)
			{
				return plugins;
			}
			for (File file : files)
			{
				if (file.isDirectory() || !file.getName().endsWith(".jar"))
				{
					continue;
				}


				try (JarFile jar = new JarFile(file);
					 MinimalClassLoader ucl = new MinimalClassLoader(new URL[]{file.toURI().toURL()}))
				{
					var elems = jar.entries();

					while (elems.hasMoreElements())
					{
						var entry = elems.nextElement();
						if (!entry.getName().endsWith(".class"))
						{
							continue;
						}

						String name = entry.getName();
						name = name.substring(0, name.length() - ".class".length())
								.replace('/', '.');

						try
						{
							var clazz = ucl.loadClass(name);
							if (!Plugin.class.isAssignableFrom(clazz)
									|| Modifier.isAbstract(clazz.getModifiers())
									|| clazz.getAnnotation(PluginDescriptor.class) == null)
							{
								continue;
							}

							Class<? extends Plugin> scriptClass = (Class<? extends Plugin>) clazz;
							plugins.add(new PluginEntry(scriptClass,
									scriptClass.getAnnotationsByType(PluginDescriptor.class)[0]));
						}
						catch (Exception | NoClassDefFoundError e)
						{
							log.error("Failed to load class: " + name, e.getMessage());
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

		return plugins;
	}

	public void startPlugin(PluginEntry entry, String... scriptArgs)
	{
		try
		{
			plugin = pluginManager.loadPlugins(List.of(entry.getScriptClass()), null)
					.stream().findFirst().orElse(null);
			if (plugin == null || !Plugins.startPlugin(plugin))
			{
				return;
			}

			pluginEntry = entry;
			args = scriptArgs;

			pluginManager.add(plugin);

			for (Key<?> key : plugin.getInjector().getBindings().keySet())
			{
				Class<?> type = key.getTypeLiteral().getRawType();
				if (Config.class.isAssignableFrom(type))
				{
					if (type.getPackageName().startsWith(plugin.getClass().getPackageName()))
					{
						config = (Config) plugin.getInjector().getInstance(key);
						configManager.setDefaultConfiguration(config, false);
					}
				}
			}

			if (plugin instanceof Script)
			{
				((Script) plugin).onStart(scriptArgs);
			}

			client.getCallbacks().post(new MinimalPluginChanged(plugin, MinimalPluginState.STARTED));
		}
		catch (PluginInstantiationException e)
		{
			throw new RuntimeException(e);
		}
	}

	public void stopPlugin()
	{
		if (!Plugins.stopPlugin(plugin))
		{
			return;
		}

		client.getCallbacks().post(new MinimalPluginChanged(plugin, MinimalPluginState.STARTED));
		pluginManager.remove(plugin);
		plugin = null;
		pluginEntry = null;
		args = null;
		config = null;
	}

	public void restartPlugin()
	{
		stopPlugin();
		startPlugin(pluginEntry, args);
	}

	public void pauseScript()
	{
		if (plugin == null || (!(plugin instanceof Script)))
		{
			return;
		}

		((Script) plugin).pauseScript();
	}

	public boolean isScriptRunning()
	{
		return plugin != null && plugin instanceof Script;
	}

	@Subscribe
	private void onMinimalPluginChanged(MinimalPluginChanged e)
	{
		log.info("Minimal Plugin state changed: {} [{}]", e.getPlugin().getName(), e.getState());

		if (e.getState() == MinimalPluginState.RESTARTING)
		{
			executorService.execute(this::restartPlugin);
		}
	}

	@Subscribe
	private void onGameTick(GameTick e)
	{
		if (minimalConfig.neverLog() && checkIdle())
		{
			randomDelay = randomDelay();
			executorService.submit(this::pressKey);
		}
	}

	@Subscribe
	private void onGameStateChanged(GameStateChanged e)
	{
		if (worldSet || e.getGameState() != GameState.LOGIN_SCREEN)
		{
			return;
		}

		Optional<Integer> worldArg = Optional.ofNullable(System.getProperty("cli.world")).map(Integer::parseInt);
		worldArg.ifPresent(this::setWorld);
		worldSet = true;
	}

	private boolean checkIdle()
	{
		int idleClientTicks = client.getKeyboardIdleTicks();
		if (client.getMouseIdleTicks() < idleClientTicks)
		{
			idleClientTicks = client.getMouseIdleTicks();
		}

		return idleClientTicks >= randomDelay;
	}

	private long randomDelay()
	{
		return (long) clamp(Math.round(ThreadLocalRandom.current().nextGaussian() * 8000));
	}

	private double clamp(double value)
	{
		return Math.max(1.0, Math.min(13000.0, value));
	}

	private void pressKey()
	{
		Keyboard.pressed(KeyEvent.VK_UP);
		Keyboard.released(KeyEvent.VK_UP);
	}

	private void setWorld(int cliWorld)
	{
		int correctedWorld = cliWorld < 300 ? cliWorld + 300 : cliWorld;

		if (correctedWorld <= 300 || client.getWorld() == correctedWorld)
		{
			return;
		}

		final WorldResult worldResult = worldService.getWorlds();

		if (worldResult == null)
		{
			log.warn("Failed to lookup worlds.");
			return;
		}

		final World world = worldResult.findWorld(correctedWorld);

		if (world != null)
		{
			final net.runelite.api.World rsWorld = client.createWorld();
			rsWorld.setActivity(world.getActivity());
			rsWorld.setAddress(world.getAddress());
			rsWorld.setId(world.getId());
			rsWorld.setPlayerCount(world.getPlayers());
			rsWorld.setLocation(world.getLocation());
			rsWorld.setTypes(WorldUtil.toWorldTypes(world.getTypes()));

			client.changeWorld(rsWorld);
			log.debug("Applied new world {}", correctedWorld);
		}
		else
		{
			log.warn("World {} not found.", correctedWorld);
		}
	}
}
