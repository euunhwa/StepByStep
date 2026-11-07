---
license: cc-by-4.0
tags:
- autonomous-driving
- prediction
- planning
- sim-agent
---
# About
The INTERACTION dataset contains naturalistic motions of various traffic participants in a variety of highly interactive driving scenarios from different countries. The dataset can serve for many behavior-related research areas, such as 
1) intention/behavior/motion prediction, 
2) behavior cloning, imitation learning, and inverse reinforcement learning,
3) behavior analysis and modeling,
4) reinforcement learning,
5) decision-making and planning algorithm development and verification,
6) interactive behavior extraction and categorization,
7) driving scenario/case/behavior generation.

# Features
<video controls width="50%">
  <source src="https://cdn-uploads.huggingface.co/production/uploads/6a8b4af1f56b9cdcb884ef0c/O1CoHV7VoJW-e6zylHY-B.mp4" type="video/mp4">
  Exemplar scenarios in INTERACTION dataset
</video>

#### International
Interactive motions from several countries, including United States , China, Germany  and Bulgaria are provided.  Similar scenarios (roundabout and merging) from different locations are included with different driving cultures and traffic rules.

#### Scenario diversity
The dataset provides trajectories in unsignalized intersections with two/all-way stops, signalized intersections with unprotected left turns, roundabounts with stop/yield signs, as well as zipper merging and lane changes in urban and highway scenarios.

#### Behavior complexity
The dataset contains complex driving behavior including negotiations, inexplicit right-of-way, irrational behavior and aggressive maneuvers.

#### Situation criticality
Critical trajectories are included in this dataset such as near-collision situations, and even a minor accident. Relevant criticality statistics were also provided.

#### HD map with full semantics
HD maps using the format of [Lanelet2](https://github.com/fzi-forschungszentrum-informatik/Lanelet2) with full semantics are also provided, such as lane connections, turn directions traffic rules, etc., which are crucial for behavior-related research.

#### Multiple views
Processing data captured from multiple views enables us to obtain accurate trajecotries and complete view from drones, and utilize the potential, unlimited resources from traffic cameras.

# Website transition
Please note that we are sunsetting the previous website [https://interaction-dataset.com](https://interaction-dataset.com) and move the dataset download and further maintenance to this Hugging Face page.

# Citation
The preprint version of our paper presenting the dataset is now available on [arXiv](https://arxiv.org/abs/1910.03088). Please cite the following paper if the INTERACTION dataset is used.
>@article{interactiondataset,
title = {{INTERACTION} {Dataset}: {An} {INTERnational}, {Adversarial} and {Cooperative} {moTION} {Dataset} in {Interactive} {Driving} {Scenarios} with {Semantic} {Maps}},
journal = {arXiv:1910.03088 [cs, eess]},
author = {Zhan, Wei and Sun, Liting and Wang, Di and Shi, Haojie and Clausse, Aubrey and Naumann, Maximilian and K\"ummerle, Julius and K\"onigshof, Hendrik and Stiller, Christoph and de La Fortelle, Arnaud and Tomizuka, Masayoshi},
month = sep,
year = {2019}}

# Dataset summary
The INTERACTION dataset provides 4 different categories of interactive driving scenarios: roundabout, un-signalized intersection, signalized intersection, merging and lane changing. In total, we have collected data from 11 locations using drones or fixed cameras. 12 folders are included in the released "recorded_trackfiles" folder. Folders with "DR_" are track files recorded via drones and folders with "TC_" are track files recorded via fixed cameras. The summary of the data is listed in the following table. 

![format](https://cdn-uploads.huggingface.co/production/uploads/6a8b4af1f56b9cdcb884ef0c/ys3fH2IqnGPgcFwfBFI2g.png)

For the 11 recording locations, we include three files for each location:
1) High definition (HD) map (xxx.osm)
2) Recorded vehicle track files (vehicle_tracks_xxx.csv)
3) Recorded pedestrian track files (pedestrian_tracks_xxx.csv)

Please note that the maps are with relative coordinate. We did not collect any information with absolute latitude or longitude, thus no real latitude or longitude can be found from the maps in the dataset. Also, only motions/trajectories and maps are provided, and no raw image is included. However, semantic label map (images) can be generated from the dataset as the input of prediction and imitation models with information of motions and maps.

# Visualization tool
We also have provided a visualization tool (in python) for the users to visualize the HD map and the tracks. You can download the visualization tool [here](https://github.com/interaction-dataset/interaction-dataset).

# Meta Information of the Track Files
This file contains all time-dependent values for each track. Information such as track id, vehicle types, vehicle sizes, current positions, current velocities, and orientations are included.

#### Recorded Vehicle Tracks Files (vehicle_tracks_xxx.csv)
- track_id: column 1. For each vehicle_tracks_xxx.csv file, the track_id starts from 1, and represent the ID of the agent.
- frame_id: column 2. For each agent (per track_id), frame_id starts from 1 and represents the frames the agent appears in the video.
- timestamp_ms: column 3. For each agent (per track_id), timestamp_ms from 100ms, and represents the time the agent appears in the video. The unit is ms.
- agent_type: column 4. It represents the types of tracked agents. For example, it can be a car, a truck and so on.
- x: column 5, the x position of the agent at each frame. The unit is m.
- y: column 6, the y position of the agent at each frame. The unit is m.
- vx: column 7, the velocity of the agent along x-direction at each frame. The unit is m/s.
- vy: column 8, the velocity of the agent along y-direction at each frame. The unit is m/s.
- psi_rad: column 9, the yaw angle of the agent at each frame. The unit is rad.
- length: column 10, the length of the agent. The unit is m.
- width: column 11, the width of the agent. The unit is m.
![track vehicle](https://cdn-uploads.huggingface.co/production/uploads/6a8b4af1f56b9cdcb884ef0c/ghykD87sVyRrhxrsbfltf.png)

#### Recorded Pedestrian Track Files (pedestrian_tracks_xxx.csv)
- track_id: column 1. For each person_tracks_xxx.csv file, the track_id starts from P1, and represent the ID of the agent. 
- frame_id: column 2. For each agent (per track_id), frame_id starts from 1 and represents the frames the agent appears in the video.
- timestamp_ms: column 3. For each agent (per track_id), timestamp_ms from 100ms, and represents the time the agent appears in the video. The unit is ms.
- agent_type: column 4. It represents the types of tracked agents as "pedestrian/bicycle".
- x: column 5, the x position of the agent at each frame. The unit is m.
- y: column 6, the y position of the agent at each frame. The unit is m.
- vx: column 7, the velocity of the agent along x-direction at each frame. The unit is m/s.
- vy: column 8, the velocity of the agent along y-direction at each frame. The unit is m/s.
![track pedestrian](https://cdn-uploads.huggingface.co/production/uploads/6a8b4af1f56b9cdcb884ef0c/kQ2nUuDgkrM8guKloVxZb.png)

# Contact
If you have any question or feedback regarding the dataset, please feel free to contact us via:
interaction.dataset@gmail.com