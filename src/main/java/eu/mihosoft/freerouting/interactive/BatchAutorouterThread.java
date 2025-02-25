/*
 *   Copyright (C) 2014  Alfons Wirtz
 *   website www.freerouting.net
 *
 *   Copyright (C) 2017 Michael Hoffer <info@michaelhoffer.de>
 *   Website www.freerouting.mihosoft.eu
*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License at <http://www.gnu.org/licenses/> 
 *   for more details.
 *
 * BatchAutorouterThread.java
 *
 * Created on 25. April 2006, 07:58
 *
 */
package eu.mihosoft.freerouting.interactive;

import eu.mihosoft.freerouting.geometry.planar.FloatPoint;
import eu.mihosoft.freerouting.geometry.planar.FloatLine;

import eu.mihosoft.freerouting.board.Unit;

import eu.mihosoft.freerouting.autoroute.BatchAutorouter;
import eu.mihosoft.freerouting.autoroute.BatchFanout;
import eu.mihosoft.freerouting.autoroute.BatchOptRoute;
import eu.mihosoft.freerouting.autoroute.BatchOptRouteMT;
import eu.mihosoft.freerouting.autoroute.BoardUpdateStrategy;
import eu.mihosoft.freerouting.autoroute.ItemSelectionStrategy;
import eu.mihosoft.freerouting.logger.FRLogger;

/**
 * GUI interactive thread for the batch autorouter.
 *
 * @author Alfons Wirtz
 */
public class BatchAutorouterThread extends InteractiveActionThread
{

    /** Creates a new instance of BatchAutorouterThread */
    protected BatchAutorouterThread(BoardHandling p_board_handling)
    {
        super(p_board_handling);
        AutorouteSettings autoroute_settings = p_board_handling.get_settings().autoroute_settings;
        this.batch_autorouter = new BatchAutorouter(this, !autoroute_settings.get_with_fanout(), true, autoroute_settings.get_start_ripup_costs());

        BoardUpdateStrategy update_strategy = p_board_handling.get_board_update_strategy();
        String hybrid_ratio = p_board_handling.get_hybrid_ratio();
        ItemSelectionStrategy item_selection_strategy = p_board_handling.get_item_selection_strategy();
        int num_threads = p_board_handling.get_num_threads();
        
        this.batch_opt_route =  num_threads > 1 ? new BatchOptRouteMT(this, num_threads, update_strategy, item_selection_strategy, hybrid_ratio)
        		                                : new BatchOptRoute(this);
    }

    protected void thread_action()
    {
        for (ThreadActionListener hl : this.listeners)
            hl.autorouterStarted();

        FRLogger.traceEntry("BatchAutorouterThread.thread_action()");
        try
        {
            java.util.ResourceBundle resources =
                    java.util.ResourceBundle.getBundle("eu.mihosoft.freerouting.interactive.InteractiveState", hdlg.get_locale());
            boolean saved_board_read_only = hdlg.is_board_read_only();
            hdlg.set_board_read_only(true);
            boolean ratsnest_hidden_before = hdlg.get_ratsnest().is_hidden();
            if (!ratsnest_hidden_before)
            {
                hdlg.get_ratsnest().hide();
            }

            FRLogger.info("Starting auto-routing...");
            FRLogger.traceEntry("BatchAutorouterThread.thread_action()-autorouting");

            String start_message = resources.getString("batch_autorouter") + " " + resources.getString("stop_message");
            hdlg.screen_messages.set_status_message(start_message);
            boolean fanout_first =
                    hdlg.get_settings().autoroute_settings.get_with_fanout() &&
                    hdlg.get_settings().autoroute_settings.get_start_pass_no() <= 1;
            if (fanout_first)
            {
                BatchFanout.fanout_board(this);
            }
            if (hdlg.get_settings().autoroute_settings.get_with_autoroute() && !this.is_stop_auto_router_requested())
            {
                batch_autorouter.autoroute_passes();
            }
            hdlg.get_routing_board().finish_autoroute();

            double autoroutingSecondsToComplete = FRLogger.traceExit("BatchAutorouterThread.thread_action()-autorouting");
            FRLogger.info("Auto-routing was completed in " + FRLogger.formatDuration(autoroutingSecondsToComplete) + ".");

            FRLogger.info("Starting routing optimization...");
            FRLogger.traceEntry("BatchAutorouterThread.thread_action()-routeoptimization");

            int via_count_before = hdlg.get_routing_board().get_vias().size();
            double trace_length_before = hdlg.coordinate_transform.board_to_user(hdlg.get_routing_board().cumulative_trace_length());

            if (hdlg.get_settings().autoroute_settings.get_with_postroute() && !this.is_stop_requested())
            {
                String opt_message = resources.getString("batch_optimizer") + " " + resources.getString("stop_message");
                hdlg.screen_messages.set_status_message(opt_message);
                this.batch_opt_route.optimize_board();
                String curr_message;
                if (this.is_stop_requested())
                {
                    curr_message = resources.getString("interrupted");
                }
                else
                {
                    curr_message = resources.getString("completed");
                }
                String end_message = resources.getString("postroute") + " " + curr_message;
                hdlg.screen_messages.set_status_message(end_message);
            }
            else
            {
                hdlg.screen_messages.clear();
                String curr_message;
                if (this.is_stop_requested())
                {
                    curr_message = resources.getString("interrupted");
                }
                else
                {
                    curr_message = resources.getString("completed");
                }
                Integer incomplete_count = hdlg.get_ratsnest().incomplete_count();
                String end_message = resources.getString("autoroute") + " " + curr_message + ", " + incomplete_count.toString() +
                        " " + resources.getString("connections_not_found");
                hdlg.screen_messages.set_status_message(end_message);
            }

            int via_count_after = hdlg.get_routing_board().get_vias().size();
            double trace_length_after = hdlg.coordinate_transform.board_to_user(hdlg.get_routing_board().cumulative_trace_length());

            double percentage_improvement = 1.0 - (((via_count_after / via_count_before) + (trace_length_after / trace_length_before)) / 2);

            double routeOptimizationSecondsToComplete = FRLogger.traceExit("BatchAutorouterThread.thread_action()-routeoptimization");
            FRLogger.info("Routing optimization was completed in " + FRLogger.formatDuration(routeOptimizationSecondsToComplete) + (percentage_improvement > 0 ? " and it improved the design by ~"+String.format("%(,.2f", percentage_improvement * 100.0)+"%" : "") + ".");

            hdlg.set_board_read_only(saved_board_read_only);
            hdlg.update_ratsnest();
            if (!ratsnest_hidden_before)
            {
                hdlg.get_ratsnest().show();
            }

            hdlg.get_panel().board_frame.refresh_windows();
            if (hdlg.get_routing_board().rules.get_trace_angle_restriction() == eu.mihosoft.freerouting.board.AngleRestriction.FORTYFIVE_DEGREE && hdlg.get_routing_board().get_test_level() != eu.mihosoft.freerouting.board.TestLevel.RELEASE_VERSION)
            {
                eu.mihosoft.freerouting.tests.Validate.multiple_of_45_degree("after autoroute: ", hdlg.get_routing_board());
            }
        } catch (Exception e)
        {
            FRLogger.error(e.getLocalizedMessage(),e);
        }

        FRLogger.traceExit("BatchAutorouterThread.thread_action()");

        for (ThreadActionListener hl : this.listeners)
        {
            if (this.is_stop_requested()) {
                hl.autorouterAborted();
            }
            else {
                hl.autorouterFinished();
            }
        }
    }

    public void draw(java.awt.Graphics p_graphics)
    {
        FloatLine curr_air_line = batch_autorouter.get_air_line();
        if (curr_air_line != null)
        {
            FloatPoint[] draw_line = new FloatPoint[2];
            draw_line[0] = curr_air_line.a;
            draw_line[1] = curr_air_line.b;
            // draw the incomplete
            java.awt.Color draw_color = this.hdlg.graphics_context.get_incomplete_color();
            double draw_width = Math.min (this.hdlg.get_routing_board().communication.get_resolution(Unit.MIL) * 3, 300);  // problem with low resolution on Kicad300;
            this.hdlg.graphics_context.draw(draw_line, draw_width, draw_color, p_graphics, 1);
        }
        FloatPoint current_opt_position = batch_opt_route.get_current_position();
        int radius = 10 * this.hdlg.get_routing_board().rules.get_default_trace_half_width(0);
        if (current_opt_position != null)
        {
            final int draw_width = 1;
            java.awt.Color draw_color = this.hdlg.graphics_context.get_incomplete_color();
            FloatPoint[] draw_points = new FloatPoint[2];
            draw_points[0] = new FloatPoint(current_opt_position.x - radius, current_opt_position.y - radius);
            draw_points[1] = new FloatPoint(current_opt_position.x + radius, current_opt_position.y + radius);
            this.hdlg.graphics_context.draw(draw_points, draw_width, draw_color, p_graphics, 1);
            draw_points[0] = new FloatPoint(current_opt_position.x + radius, current_opt_position.y - radius);
            draw_points[1] = new FloatPoint(current_opt_position.x - radius, current_opt_position.y + radius);
            this.hdlg.graphics_context.draw(draw_points, draw_width, draw_color, p_graphics, 1);
            this.hdlg.graphics_context.draw_circle(current_opt_position, radius, draw_width, draw_color, p_graphics, 1);
        }
    }
    private final BatchAutorouter batch_autorouter;
    private final BatchOptRoute batch_opt_route;
}
