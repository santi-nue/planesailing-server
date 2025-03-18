
using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using NLog;

namespace PlaneSailing.Comms
{
    public class Dump1090JSONReader : Client
    {
        private static readonly Logger Logger = LogManager.GetCurrentClassLogger();
        private static readonly int QueryIntervalMs = 5000;
        private readonly Uri _url;
        private readonly CancellationTokenSource _cancellationTokenSource = new();
        private readonly Task _readerTask;

        public Dump1090JSONReader(string name, string url, TrackTable trackTable) : base(name, trackTable)
        {
            try
            {
                _url = new Uri(url);
            }
            catch (UriFormatException e)
            {
                Logger.Error($"{url} is an invalid URL, {name} client could not be created!");
            }

            _readerTask = Task.Factory.StartNew(async () => await RunAsync(_cancellationTokenSource.Token), 
                _cancellationTokenSource.Token, TaskCreationOptions.LongRunning, TaskScheduler.Default);
        }

        public override void Run()
        {
            Online = true;
        }

        public override void Stop()
        {
            Online = false;
            _cancellationTokenSource.Cancel();
            _readerTask.Wait();
        }

        public override ClientType GetType() => ClientType.ADSB;

        protected override Logger GetLogger() => Logger;

        protected override int GetTimeoutMillis() => QueryIntervalMs * 2;

        private async Task RunAsync(CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await ReaderTask();
                    await Task.Delay(QueryIntervalMs, token);
                }
                catch (TaskCanceledException)
                {
                    break;
                }
                catch (Exception e)
                {
                    Logger.Error($"Exception reading Dump1090 JSON data on connection {Name}", e);
                }
            }
        }

        private async Task ReaderTask()
        {
            try
            {
                using var webClient = new WebClient();
                string json = await webClient.DownloadStringTaskAsync(_url);
                var o = JObject.Parse(json);
                UpdatePacketReceivedTime();

                // Extract data
                var acList = (JArray)o["aircraft"];
                if (acList != null)
                {
                    foreach (var ac in acList)
                    {
                        try
                        {
                            // Get the ICAO 24-bit hex code
                            string icao24 = ac["hex"].ToString();

                            // If this is a new track, add it to the track table
                            if (!TrackTable.ContainsKey(icao24))
                            {
                                TrackTable[icao24] = new Aircraft(icao24);
                            }

                            // Extract the data and update the track
                            var a = (Aircraft)TrackTable[icao24];
                            a.Callsign = ac.Value<string>("flight")?.Trim();
                            a.Squawk = ac.Value<int?>("squawk");
                            a.Category = ac.Value<string>("category")?.Trim();

                            if (ac["lat"] != null && ac["lon"] != null)
                            {
                                long? time = null;
                                if (ac["pos_seen"] != null)
                                {
                                    time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - 
                                           (long)(ac.Value<double>("pos_seen") * 1000);
                                }
                                else if (ac["seen"] != null)
                                {
                                    time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - 
                                           (long)(ac.Value<double>("seen") * 1000);
                                }
                                a.AddPosition(ac.Value<double>("lat"), ac.Value<double>("lon"), time);
                            }

                            if (ac["altitude"] != null)
                            {
                                if (ac["altitude"].Type == JTokenType.String && ac["altitude"].ToString() == "ground")
                                {
                                    a.Altitude = 0.0;
                                    a.OnGround = true;
                                }
                                else
                                {
                                    a.Altitude = ac.Value<double>("altitude");
                                    a.OnGround = false;
                                }
                            }
                            else if (ac["alt_baro"] != null)
                            {
                                if (ac["alt_baro"].Type == JTokenType.String && ac["alt_baro"].ToString() == "ground")
                                {
                                    a.Altitude = 0.0;
                                    a.OnGround = true;
                                }
                                else
                                {
                                    a.Altitude = ac.Value<double>("alt_baro");
                                    a.OnGround = false;
                                }
                            }
                            else if (ac["alt_geom"] != null)
                            {
                                if (ac["alt_geom"].Type == JTokenType.String && ac["alt_geom"].ToString() == "ground")
                                {
                                    a.Altitude = 0.0;
                                    a.OnGround = true;
                                }
                                else
                                {
                                    a.Altitude = ac.Value<double>("alt_geom");
                                    a.OnGround = false;
                                }
                            }
                            else if (ac["nav_altitude_mcp"] != null)
                            {
                                if (ac["nav_altitude_mcp"].Type == JTokenType.String && ac["nav_altitude_mcp"].ToString() == "ground")
                                {
                                    a.Altitude = 0.0;
                                    a.OnGround = true;
                                }
                                else
                                {
                                    a.Altitude = ac.Value<double>("nav_altitude_mcp");
                                    a.OnGround = false;
                                }
                            }

                            a.VerticalRate = ac.Value<double?>("vert_rate") / 60.0 ??
                                             ac.Value<double?>("baro_rate") / 60.0 ??
                                             ac.Value<double?>("geom_rate") / 60.0;

                            a.Course = ac.Value<double?>("track") ??
                                       ac.Value<double?>("true_heading") ??
                                       ac.Value<double?>("mag_heading") ??
                                       ac.Value<double?>("nav_heading");

                            a.Heading = ac.Value<double?>("true_heading") ??
                                        ac.Value<double?>("mag_heading") ??
                                        ac.Value<double?>("nav_heading") ??
                                        ac.Value<double?>("track");

                            a.Speed = ac.Value<double?>("gs") ??
                                      ac.Value<double?>("tas") ??
                                      ac.Value<double?>("ias") ??
                                      ac.Value<double?>("mach") * 666.739;

                            long metaTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - 
                                            (long)(ac.Value<double?>("pos_seen") * 1000 ??
                                                   ac.Value<double?>("seen") * 1000);
                            a.UpdateMetadataTime(metaTime);
                        }
                        catch (Exception e)
                        {
                            Logger.Error("Exception reading data for an aircraft", e);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Logger.Error("Exception reading Dump1090 JSON data", e);
            }
        }
    }
}
